package de.intranda.goobi.plugins.step.mixedocr;

import lombok.AllArgsConstructor;
import lombok.Data;

@Data
@AllArgsConstructor
public class ItmRequest {
    private String goobiId;
    private String sourceDir;
    private String targetDir;
    private String fontType;
    private int priority;
    private String postOCR;
    private String titleprefix;
    private String templatename;
    private String jobtype;
    private String serverType;
    private String title;
    private String goobiTitle;
}
