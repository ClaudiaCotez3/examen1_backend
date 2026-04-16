# Diseño de Base de Datos MongoDB
## Sistema de Gestión de Trámites basado en Políticas de Negocio (Workflow Engine)

**Versión:** 1.0
**Motor:** MongoDB 6.x+
**Arquitectura:** NoSQL orientada a documentos
**Patrón principal:** Híbrido (Referencing + Embedding selectivo)

---

## 1. Filosofía del Diseño

El sistema combina **dos naturalezas de datos**:

| Naturaleza | Tipo de datos | Estrategia |
|------------|---------------|------------|
| **Diseño** (estructura del workflow) | Políticas, calles, actividades, flujos, formularios | Referencias normalizadas + versionado inmutable |
| **Ejecución** (runtime) | Trámites, instancias, respuestas, historial | Embedding agresivo para lectura rápida |

**Principio rector:** *"Optimiza para la consulta más frecuente"*. La operación más recurrente es **"¿en qué estado está el trámite X?"** y **"¿qué tareas tengo asignadas?"**, por lo que el modelo prioriza esas dos lecturas.

---

## 2. Decisiones Arquitectónicas Clave

### 2.1 Versionado inmutable (`versiones_politica`)
Una política activa **nunca se modifica**: se crea una nueva versión. Los trámites en curso siguen ejecutando la versión con la que iniciaron. Esto evita corrupción de workflows en ejecución.

### 2.2 Snapshot del workflow en el trámite
Al iniciar un trámite, se **embebe un snapshot reducido** de la versión de política (calles, actividades, flujos). Razón: las políticas pueden tener miles de trámites históricos consultando su estructura — sin snapshot, cada lectura haría 4-5 joins.

### 2.3 Referencing entre entidades de diseño
Las entidades de diseño (`politicas_negocio`, `calles`, `actividades`, `flujos`, `formularios`, `campos_formulario`) usan **referencias por `_id`** porque:
- Se modifican juntas durante el diseño (CRUD frecuente).
- El editor visual las pinta independientemente.
- Permite reutilizar formularios/campos.

### 2.4 Embedding en runtime
`respuestas_formulario` se mantiene como colección separada (no embebida en `instancias_actividad`) porque:
- Un formulario puede tener 100+ campos (límite 16MB del documento).
- Permite indexar/buscar respuestas individuales (analítica).

### 2.5 Historial como colección append-only
`historial_tramite` es **inmutable y solo-escritura**, ideal para auditoría y reconstrucción de eventos (event sourcing ligero).

---

## 3. Diseño Detallado por Colección

### 3.1 `usuarios`

```json
{
  "_id": ObjectId("652f1a..."),
  "nombre": "María González",
  "email": "maria.gonzalez@empresa.com",
  "password": "$2b$12$KIXxPfnK8L9...",
  "rol_id": ObjectId("652f0a..."),
  "activo": true,
  "fecha_creacion": ISODate("2026-01-15T08:30:00Z")
}
```

**Índices:**
```js
db.usuarios.createIndex({ email: 1 }, { unique: true })
db.usuarios.createIndex({ rol_id: 1, activo: 1 })
```

**Justificación:** `email` único para login (O(log n)). Índice compuesto `rol_id+activo` acelera el listado de funcionarios activos por rol (consulta operativa frecuente).

---

### 3.2 `roles`

```json
{
  "_id": ObjectId("652f0a..."),
  "nombre": "funcionario_juridico",
  "descripcion": "Funcionario del área jurídica con permisos para revisar contratos"
}
```

**Índices:**
```js
db.roles.createIndex({ nombre: 1 }, { unique: true })
```

**Justificación:** Colección pequeña y estable. No requiere índices adicionales.

---

### 3.3 `politicas_negocio`

```json
{
  "_id": ObjectId("652fa0..."),
  "nombre": "Solicitud de Licencia de Construcción",
  "descripcion": "Trámite municipal para aprobar permisos de obra civil",
  "estado": "activa",
  "fecha_creacion": ISODate("2026-02-01T10:00:00Z")
}
```

**Índices:**
```js
db.politicas_negocio.createIndex({ estado: 1, fecha_creacion: -1 })
db.politicas_negocio.createIndex({ nombre: "text" })
```

**Justificación:** Listar políticas activas ordenadas por fecha es la vista principal del catálogo. Índice de texto para búsqueda libre.

---

### 3.4 `versiones_politica`

```json
{
  "_id": ObjectId("652fb1..."),
  "politica_id": ObjectId("652fa0..."),
  "numero_version": 3,
  "estado": "activa",
  "fecha_publicacion": ISODate("2026-03-10T14:20:00Z")
}
```

**Índices:**
```js
db.versiones_politica.createIndex({ politica_id: 1, numero_version: -1 })
db.versiones_politica.createIndex({ politica_id: 1, estado: 1 })
```

**Regla de negocio:** Solo **una** versión `activa` por política. Aplicar en capa de servicio o con índice parcial único:
```js
db.versiones_politica.createIndex(
  { politica_id: 1 },
  { unique: true, partialFilterExpression: { estado: "activa" } }
)
```

---

### 3.5 `calles` (departamentos del workflow)

```json
{
  "_id": ObjectId("652fc2..."),
  "politica_id": ObjectId("652fa0..."),
  "nombre": "Departamento Jurídico",
  "posicion": 2
}
```

**Índices:**
```js
db.calles.createIndex({ politica_id: 1, posicion: 1 })
```

**Justificación:** El editor visual renderiza las calles ordenadas por `posicion` para una política dada.

---

### 3.6 `actividades`

```json
{
  "_id": ObjectId("652fd3..."),
  "politica_id": ObjectId("652fa0..."),
  "calle_id": ObjectId("652fc2..."),
  "nombre": "Revisar documentación legal",
  "tipo": "normal",
  "requiere_formulario": true
}
```

**Índices:**
```js
db.actividades.createIndex({ politica_id: 1, calle_id: 1 })
db.actividades.createIndex({ politica_id: 1, tipo: 1 })
```

**Justificación:** Recuperar todas las actividades de una calle (renderizado del grafo) y localizar nodos `inicio`/`fin` rápidamente.

---

### 3.7 `flujos` (aristas del grafo)

```json
{
  "_id": ObjectId("652fe4..."),
  "actividad_origen_id": ObjectId("652fd3..."),
  "actividad_destino_id": ObjectId("652fd4..."),
  "tipo": "condicional",
  "condicion": "monto > 50000"
}
```

**Índices:**
```js
db.flujos.createIndex({ actividad_origen_id: 1 })
db.flujos.createIndex({ actividad_destino_id: 1 })
```

**Justificación:** El motor de workflow consulta "¿cuáles son los siguientes pasos desde la actividad X?" en cada transición.

---

### 3.8 `formularios`

```json
{
  "_id": ObjectId("652ff5..."),
  "actividad_id": ObjectId("652fd3...")
}
```

**Índices:**
```js
db.formularios.createIndex({ actividad_id: 1 }, { unique: true })
```

**Justificación:** Relación 1:1 con actividad. Único garantiza integridad.

---

### 3.9 `campos_formulario`

```json
{
  "_id": ObjectId("653006..."),
  "formulario_id": ObjectId("652ff5..."),
  "nombre": "numero_predio",
  "tipo": "texto",
  "requerido": true
}
```

**Índices:**
```js
db.campos_formulario.createIndex({ formulario_id: 1 })
```

---

### 3.10 `tramites` ⭐ (núcleo del runtime)

```json
{
  "_id": ObjectId("653117..."),
  "codigo": "TRM-2026-000847",
  "version_politica_id": ObjectId("652fb1..."),
  "estado": "activo",
  "fecha_inicio": ISODate("2026-04-15T09:15:00Z")
}
```

**Índices:**
```js
db.tramites.createIndex({ codigo: 1 }, { unique: true })
db.tramites.createIndex({ estado: 1, fecha_inicio: -1 })
db.tramites.createIndex({ version_politica_id: 1, estado: 1 })
```

**Mejora sugerida (sin romper el modelo):** Añadir un campo cacheado `actividad_actual_id` actualizado por el motor en cada transición, para responder "¿en qué paso está?" en O(1) sin recorrer instancias.

---

### 3.11 `instancias_actividad` ⭐ (tabla más consultada)

```json
{
  "_id": ObjectId("653228..."),
  "tramite_id": ObjectId("653117..."),
  "actividad_id": ObjectId("652fd3..."),
  "estado": "en_proceso",
  "asignado_a": ObjectId("652f1a..."),
  "fecha_inicio": ISODate("2026-04-15T10:00:00Z"),
  "fecha_fin": null
}
```

**Índices (críticos):**
```js
db.instancias_actividad.createIndex({ asignado_a: 1, estado: 1, fecha_inicio: -1 })
db.instancias_actividad.createIndex({ tramite_id: 1, estado: 1 })
db.instancias_actividad.createIndex({ actividad_id: 1, estado: 1 })
```

**Justificación:**
- Índice 1: bandeja del funcionario ("mis tareas pendientes").
- Índice 2: estado actual del trámite.
- Índice 3: cuello de botella por actividad (analítica operativa).

---

### 3.12 `respuestas_formulario`

```json
{
  "_id": ObjectId("653339..."),
  "instancia_actividad_id": ObjectId("653228..."),
  "campo_id": ObjectId("653006..."),
  "valor": "PRD-2026-7741"
}
```

**Índices:**
```js
db.respuestas_formulario.createIndex({ instancia_actividad_id: 1 })
db.respuestas_formulario.createIndex({ campo_id: 1, valor: 1 })
```

**Nota:** Para campos tipo `archivo`, `valor` debe contener un URI a almacenamiento externo (S3, GridFS), nunca el binario.

---

### 3.13 `historial_tramite` (event log)

```json
{
  "_id": ObjectId("65344a..."),
  "tramite_id": ObjectId("653117..."),
  "actividad_id": ObjectId("652fd3..."),
  "usuario_id": ObjectId("652f1a..."),
  "accion": "FORMULARIO_ENVIADO",
  "fecha": ISODate("2026-04-15T11:42:00Z")
}
```

**Índices:**
```js
db.historial_tramite.createIndex({ tramite_id: 1, fecha: -1 })
db.historial_tramite.createIndex({ usuario_id: 1, fecha: -1 })
```

**Mejora sugerida:** Convertir en **time-series collection** (MongoDB 5.0+) si el volumen supera 10M docs/año:
```js
db.createCollection("historial_tramite", {
  timeseries: { timeField: "fecha", metaField: "tramite_id", granularity: "minutes" }
})
```

---

### 3.14 `kpis` (métricas precalculadas)

```json
{
  "_id": ObjectId("65355b..."),
  "politica_id": ObjectId("652fa0..."),
  "actividad_id": ObjectId("652fd3..."),
  "tiempo_promedio": 2845.7,
  "cantidad": 1284,
  "eficiencia": 0.87
}
```

**Índices:**
```js
db.kpis.createIndex({ politica_id: 1, actividad_id: 1 }, { unique: true })
```

**Justificación:** Materializar KPIs vía job nocturno (Spring `@Scheduled` + Aggregation Pipeline) evita recalcular en cada dashboard.

---

### 3.15 `registros_ia`

```json
{
  "_id": ObjectId("65366c..."),
  "tramite_id": ObjectId("653117..."),
  "tipo": "clasificacion",
  "input": "Solicitud de permiso para construcción de 3 niveles en zona residencial...",
  "output": "{\"categoria\":\"VIVIENDA_MULTIFAMILIAR\",\"riesgo\":\"medio\",\"confianza\":0.92}",
  "fecha": ISODate("2026-04-15T09:16:30Z")
}
```

**Índices:**
```js
db.registros_ia.createIndex({ tramite_id: 1, fecha: -1 })
db.registros_ia.createIndex({ tipo: 1, fecha: -1 })
```

---

## 4. Mapa de Relaciones

```
roles ──< usuarios
                  └──< instancias_actividad.asignado_a
                  └──< historial_tramite.usuario_id

politicas_negocio ──< versiones_politica ──< tramites
                  ├──< calles ──< actividades ──< flujos
                                              └── formularios ──< campos_formulario

tramites ──< instancias_actividad ──< respuestas_formulario
        └──< historial_tramite
        └──< registros_ia

politicas_negocio + actividades ──< kpis
```

`──<` = uno-a-muchos por referencia (`ObjectId`).

---

## 5. Consultas Clave

### 5.1 Estado actual de un trámite

```js
db.tramites.aggregate([
  { $match: { codigo: "TRM-2026-000847" } },
  { $lookup: {
      from: "instancias_actividad",
      let: { tid: "$_id" },
      pipeline: [
        { $match: { $expr: { $eq: ["$tramite_id", "$$tid"] } } },
        { $sort: { fecha_inicio: -1 } },
        { $lookup: {
            from: "actividades",
            localField: "actividad_id",
            foreignField: "_id",
            as: "actividad"
        }},
        { $unwind: "$actividad" }
      ],
      as: "instancias"
  }}
])
```

### 5.2 Tareas de un funcionario (bandeja de entrada)

```js
db.instancias_actividad.find({
  asignado_a: ObjectId("652f1a..."),
  estado: { $in: ["en_espera", "en_proceso"] }
})
.sort({ fecha_inicio: 1 })
.limit(50)
```
*Usa el índice compuesto `{ asignado_a: 1, estado: 1, fecha_inicio: -1 }` — covered query.*

### 5.3 Historial completo de un trámite

```js
db.historial_tramite.aggregate([
  { $match: { tramite_id: ObjectId("653117...") } },
  { $sort: { fecha: 1 } },
  { $lookup: { from: "usuarios", localField: "usuario_id", foreignField: "_id", as: "usuario" } },
  { $lookup: { from: "actividades", localField: "actividad_id", foreignField: "_id", as: "actividad" } },
  { $project: {
      fecha: 1,
      accion: 1,
      "usuario.nombre": 1,
      "actividad.nombre": 1
  }}
])
```

### 5.4 KPI: actividades cuello de botella

```js
db.instancias_actividad.aggregate([
  { $match: { estado: "finalizado", fecha_fin: { $ne: null } } },
  { $project: {
      actividad_id: 1,
      duracion: { $subtract: ["$fecha_fin", "$fecha_inicio"] }
  }},
  { $group: {
      _id: "$actividad_id",
      tiempo_promedio_ms: { $avg: "$duracion" },
      cantidad: { $sum: 1 }
  }},
  { $sort: { tiempo_promedio_ms: -1 } },
  { $limit: 10 }
])
```

---

## 6. Estrategia de Escalabilidad

| Aspecto | Estrategia |
|---------|------------|
| **Sharding** | `tramites`, `instancias_actividad`, `historial_tramite` por `tramite_id` (hashed) — distribuye carga uniformemente. |
| **Read scaling** | Replica set con lecturas secundarias para dashboards y KPIs. |
| **Archivado** | Trámites finalizados >2 años → colección `tramites_archivo` (cold storage). |
| **Write throughput** | `historial_tramite` y `registros_ia` con `writeConcern: { w: 1 }` (no críticos). Trámites con `w: "majority"`. |
| **Caché** | Redis en frente para `usuarios`, `roles`, `politicas_negocio` activas. |

---

## 7. Mejoras Sugeridas (no rompen el modelo base)

1. **Soft delete** vía campo `eliminado: boolean` en entidades de diseño (auditoría regulatoria).
2. **Optimistic locking** con campo `version: int` en `instancias_actividad` (evita race conditions en asignación concurrente).
3. **TTL index** en `registros_ia` para purgar logs de IA >180 días.
4. **Validación de esquema** con `$jsonSchema` por colección (defensa en profundidad).
5. **Change streams** sobre `instancias_actividad` para notificaciones en tiempo real (WebSocket).

---

## 8. Resumen de Índices (Cheat Sheet)

```js
// Críticos para runtime
db.instancias_actividad.createIndex({ asignado_a: 1, estado: 1, fecha_inicio: -1 })
db.instancias_actividad.createIndex({ tramite_id: 1, estado: 1 })
db.tramites.createIndex({ codigo: 1 }, { unique: true })
db.tramites.createIndex({ estado: 1, fecha_inicio: -1 })

// Diseño de workflow
db.actividades.createIndex({ politica_id: 1, calle_id: 1 })
db.flujos.createIndex({ actividad_origen_id: 1 })
db.calles.createIndex({ politica_id: 1, posicion: 1 })

// Auditoría / analítica
db.historial_tramite.createIndex({ tramite_id: 1, fecha: -1 })
db.kpis.createIndex({ politica_id: 1, actividad_id: 1 }, { unique: true })

// Identidad
db.usuarios.createIndex({ email: 1 }, { unique: true })
db.roles.createIndex({ nombre: 1 }, { unique: true })
```

---

**Fin del documento.**
