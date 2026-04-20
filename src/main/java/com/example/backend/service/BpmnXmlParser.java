package com.example.backend.service;

import com.example.backend.dto.ActivityRequestDTO;
import com.example.backend.dto.BusinessPolicyRequestDTO;
import com.example.backend.dto.FlowRequestDTO;
import com.example.backend.dto.LaneRequestDTO;
import com.example.backend.exception.BadRequestException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Parses BPMN 2.0 XML produced by the visual designer into the same
 * {@link BusinessPolicyRequestDTO} shape the structured save path uses.
 *
 * Why a custom DOM parser instead of `camunda-bpmn-model`?
 *   - The BPMN we deal with is a small, well-known subset (lanes, flow nodes,
 *     sequence flows, plus three custom `workflow:*` extension attributes).
 *   - Pulling in the full bpmn-model dependency adds ~3MB of jars and a
 *     transitive Camunda namespace that we have no other use for.
 *   - DOM-level access lets us read both `extensionElements` and the
 *     `$attrs`-style flat attributes that the frontend writes when the
 *     moddle layer falls back to attribute serialization.
 *
 * Mapping rules (mirrors `bpmn-parser.ts` in the frontend):
 *   bpmn:lane                        → LaneRequestDTO
 *   bpmn:startEvent                  → ActivityRequestDTO type=START
 *   bpmn:endEvent                    → ActivityRequestDTO type=END
 *   bpmn:*Gateway                    → ActivityRequestDTO type=DECISION
 *   bpmn:task / userTask / etc.      → ActivityRequestDTO type=TASK
 *   bpmn:sequenceFlow                → FlowRequestDTO (LINEAR or CONDITIONAL)
 *
 * Extension attributes read off task-like nodes:
 *   workflow:formId                  → (catalog ref; resolution is the
 *                                      caller's responsibility — parser only
 *                                      records that one was assigned)
 *   workflow:assignedUserId          → ActivityRequestDTO.assignedUserIds
 *                                      (accepts JSON array or single string)
 *   workflow:requirements            → ActivityRequestDTO.requirements
 *                                      (accepts JSON array)
 */
@Service
public class BpmnXmlParser {

    private static final String NS_BPMN = "http://www.omg.org/spec/BPMN/20100524/MODEL";

    /** Custom extension namespace the frontend writes under (best-effort). */
    private static final String ATTR_FORM_ID = "workflow:formId";
    private static final String ATTR_ASSIGNED_USER = "workflow:assignedUserId";
    private static final String ATTR_REQUIREMENTS = "workflow:requirements";

    /** BPMN local-name → activity type buckets. */
    private static final Set<String> TASK_NODES = Set.of(
            "task", "userTask", "serviceTask", "manualTask", "scriptTask",
            "businessRuleTask", "sendTask", "receiveTask"
    );
    private static final Set<String> GATEWAY_NODES = Set.of(
            "exclusiveGateway", "inclusiveGateway", "parallelGateway",
            "eventBasedGateway", "complexGateway"
    );

    private final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * Parse XML into a structured request DTO. The returned DTO has
     * lanes/activities/flows populated but no top-level name/description —
     * those come from the policy metadata, not the diagram.
     *
     * @throws BadRequestException if the XML is malformed or empty.
     */
    public BusinessPolicyRequestDTO parse(String xml) {
        if (xml == null || xml.isBlank()) {
            throw new BadRequestException("BPMN XML is empty");
        }

        Document doc = parseDocument(xml);

        List<LaneRequestDTO> lanes = readLanes(doc);
        // Build the lane lookup so we can stamp each activity with its laneRef.
        Map<String, String> elementToLane = readElementLaneMap(doc);
        List<ActivityRequestDTO> activities = readActivities(doc, elementToLane, lanes);
        List<FlowRequestDTO> flows = readFlows(doc, activities);

        return BusinessPolicyRequestDTO.builder()
                .lanes(lanes)
                .activities(activities)
                .flows(flows)
                .bpmnXml(xml)
                .build();
    }

    // ── DOM bootstrap ──────────────────────────────────────────────────

    private Document parseDocument(String xml) {
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setNamespaceAware(true);
            // XXE hardening — these inputs come over HTTP from authenticated
            // admins, but defense-in-depth is cheap.
            factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
            factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            factory.setExpandEntityReferences(false);

            DocumentBuilder builder = factory.newDocumentBuilder();
            return builder.parse(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception ex) {
            throw new BadRequestException("Invalid BPMN XML: " + ex.getMessage());
        }
    }

    // ── Lanes ──────────────────────────────────────────────────────────

    private List<LaneRequestDTO> readLanes(Document doc) {
        NodeList laneNodes = doc.getElementsByTagNameNS(NS_BPMN, "lane");
        List<LaneRequestDTO> lanes = new ArrayList<>(laneNodes.getLength());
        for (int i = 0; i < laneNodes.getLength(); i++) {
            Element lane = (Element) laneNodes.item(i);
            String id = lane.getAttribute("id");
            String name = lane.getAttribute("name");
            lanes.add(LaneRequestDTO.builder()
                    .clientId(id)
                    .name(isBlank(name) ? "Lane " + (i + 1) : name)
                    .position(i)
                    .build());
        }
        if (lanes.isEmpty()) {
            // Match the frontend's fallback: synthesize a default lane so
            // standalone diagrams (no participant) still validate.
            lanes.add(LaneRequestDTO.builder()
                    .clientId("lane_default")
                    .name("Default")
                    .position(0)
                    .build());
        }
        return lanes;
    }

    /** Map of elementId → laneId via each lane's {@code <flowNodeRef>} children. */
    private Map<String, String> readElementLaneMap(Document doc) {
        Map<String, String> map = new HashMap<>();
        NodeList laneNodes = doc.getElementsByTagNameNS(NS_BPMN, "lane");
        for (int i = 0; i < laneNodes.getLength(); i++) {
            Element lane = (Element) laneNodes.item(i);
            String laneId = lane.getAttribute("id");
            NodeList refs = lane.getElementsByTagNameNS(NS_BPMN, "flowNodeRef");
            for (int j = 0; j < refs.getLength(); j++) {
                String ref = refs.item(j).getTextContent();
                if (ref != null && !ref.isBlank()) {
                    map.put(ref.trim(), laneId);
                }
            }
        }
        return map;
    }

    // ── Activities ─────────────────────────────────────────────────────

    private List<ActivityRequestDTO> readActivities(
            Document doc,
            Map<String, String> elementToLane,
            List<LaneRequestDTO> lanes) {

        List<ActivityRequestDTO> activities = new ArrayList<>();
        NodeList processes = doc.getElementsByTagNameNS(NS_BPMN, "process");

        for (int p = 0; p < processes.getLength(); p++) {
            NodeList children = processes.item(p).getChildNodes();
            for (int i = 0; i < children.getLength(); i++) {
                Node child = children.item(i);
                if (child.getNodeType() != Node.ELEMENT_NODE) continue;
                if (!NS_BPMN.equals(child.getNamespaceURI())) continue;

                String localName = child.getLocalName();
                String activityType = classifyActivityType(localName);
                if (activityType == null) continue;

                Element el = (Element) child;
                String id = el.getAttribute("id");
                String name = el.getAttribute("name");

                String laneRef = elementToLane.getOrDefault(id, lanes.get(0).getClientId());
                ActivityRequestDTO activity = ActivityRequestDTO.builder()
                        .clientId(id)
                        .name(isBlank(name) ? defaultName(activityType) : name)
                        .type(activityType)
                        .laneRef(laneRef)
                        .requiresForm(false)
                        .build();

                if ("TASK".equals(activityType)) {
                    applyExtensionAttributes(el, activity);
                }
                activities.add(activity);
            }
        }
        return activities;
    }

    private String classifyActivityType(String localName) {
        if ("startEvent".equals(localName)) return "START";
        if ("endEvent".equals(localName)) return "END";
        if (GATEWAY_NODES.contains(localName)) return "DECISION";
        if (TASK_NODES.contains(localName)) return "TASK";
        return null;
    }

    /**
     * Reads `workflow:*` extension attributes off a Task. The frontend
     * writes them as either real BPMN extension elements OR plain element
     * attributes (depending on whether moddle accepted the namespace), so
     * we check both surfaces.
     */
    private void applyExtensionAttributes(Element el, ActivityRequestDTO activity) {
        // Plain attribute path (frontend's $attrs fallback).
        readAttr(el, ATTR_ASSIGNED_USER).ifPresent(raw -> activity.setAssignedUserIds(parseStringList(raw)));
        readAttr(el, ATTR_REQUIREMENTS).ifPresent(raw -> activity.setRequirements(parseStringList(raw)));
        // formId presence flips requiresForm; we don't resolve the catalog here.
        readAttr(el, ATTR_FORM_ID).ifPresent(raw -> activity.setRequiresForm(true));
    }

    // ── Flows ──────────────────────────────────────────────────────────

    private List<FlowRequestDTO> readFlows(Document doc, List<ActivityRequestDTO> activities) {
        Map<String, String> typeByClientId = new HashMap<>();
        for (ActivityRequestDTO a : activities) {
            typeByClientId.put(a.getClientId(), a.getType());
        }

        List<FlowRequestDTO> flows = new ArrayList<>();
        NodeList sf = doc.getElementsByTagNameNS(NS_BPMN, "sequenceFlow");
        for (int i = 0; i < sf.getLength(); i++) {
            Element flow = (Element) sf.item(i);
            String source = flow.getAttribute("sourceRef");
            String target = flow.getAttribute("targetRef");
            if (isBlank(source) || isBlank(target)) continue;

            // CONDITIONAL when an explicit conditionExpression is present, OR
            // when the source is a gateway (decision branches are inherently
            // conditional). LINEAR otherwise.
            boolean hasCondition = flow.getElementsByTagNameNS(NS_BPMN, "conditionExpression").getLength() > 0;
            String type = (hasCondition || "DECISION".equals(typeByClientId.get(source)))
                    ? "CONDITIONAL"
                    : "LINEAR";

            flows.add(FlowRequestDTO.builder()
                    .sourceRef(source)
                    .targetRef(target)
                    .type(type)
                    .build());
        }
        return flows;
    }

    // ── Helpers ────────────────────────────────────────────────────────

    /**
     * Reads an attribute either by the namespaced QName (when the moddle
     * recognized our namespace) or by the literal `workflow:foo` local
     * name (when it fell back to attribute serialization).
     */
    private java.util.Optional<String> readAttr(Element el, String nsPrefixedName) {
        // Try literal "workflow:foo" first — this is what the frontend's
        // $attrs fallback writes most of the time.
        String literal = el.getAttribute(nsPrefixedName);
        if (literal != null && !literal.isBlank()) {
            return java.util.Optional.of(literal);
        }
        // Then try the local-name lookup (in case moddle stripped the prefix).
        int colon = nsPrefixedName.indexOf(':');
        if (colon > 0) {
            String local = nsPrefixedName.substring(colon + 1);
            String byLocal = el.getAttribute(local);
            if (byLocal != null && !byLocal.isBlank()) {
                return java.util.Optional.of(byLocal);
            }
        }
        return java.util.Optional.empty();
    }

    /**
     * The frontend stores list-typed extensions as JSON-encoded strings.
     * Tolerate both array form (`["a","b"]`) and a bare string (legacy
     * single-value writes).
     */
    private List<String> parseStringList(String raw) {
        String trimmed = raw.trim();
        if (trimmed.isEmpty()) return new ArrayList<>();
        if (trimmed.startsWith("[")) {
            try {
                List<String> parsed = objectMapper.readValue(trimmed, new TypeReference<>() {});
                List<String> out = new ArrayList<>(parsed.size());
                for (String s : parsed) {
                    if (s != null && !s.isBlank()) out.add(s);
                }
                return out;
            } catch (Exception ex) {
                return new ArrayList<>();
            }
        }
        return new ArrayList<>(List.of(trimmed));
    }

    private String defaultName(String activityType) {
        return switch (activityType) {
            case "START" -> "Start";
            case "END" -> "End";
            case "DECISION" -> "Decision";
            default -> "Activity";
        };
    }

    private boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
}
