package com.example.backend.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Gestión Documental — un cambio de contenido emitido por un co-editor.
 * Igual que el diagrama BPMN colaborativo, se difunde el ESTADO completo
 * del editor (no deltas): el contenido serializado según el modo del
 * editor (texto plano, HTML del editor de Word, matriz JSON de la hoja
 * Excel). Último-en-escribir gana; el binario real solo se persiste al
 * "Guardar nueva versión".
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CollabDocContentDTO {

    /** Sellado por el servidor para que el emisor ignore su propio eco. */
    private String senderEmail;

    /** text | rich | sheet — modo del editor que emitió. */
    private String mode;

    /** Estado serializado del editor (string / HTML / JSON de matriz). */
    private String content;
}
