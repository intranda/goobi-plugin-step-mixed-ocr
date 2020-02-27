package de.intranda.goobi.plugins.step.mixedocr;

import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.jdom2.Document;
import org.jdom2.JDOMException;
import org.jdom2.input.SAXBuilder;
import org.junit.Test;

public class TestMixedOcrPlugin {
    @Test
    public void testFilterMissingAlto() {
        Set<String> altoNames = new HashSet<>(Collections.singletonList("0001.xml"));
        String[] imageNames = new String[] { "0001.tif", "0002.tif" };
        List<String> missing = MixedOcrPlugin.filterMissingAlto(altoNames, imageNames);
        assertTrue(missing.contains("0002.xml"));
    }

    @Test
    public void testCreateEmptyAlto() throws IOException, JDOMException {
        Path tempPath = Files.createTempFile("mixedocr_emptyalto_", ".xml");
        try {
            MixedOcrPlugin.createEmptyAlto(tempPath);
            SAXBuilder sax = new SAXBuilder();
            try (InputStream in = Files.newInputStream(tempPath)) {
                Document doc = sax.build(in);
                assertTrue(doc.getRootElement() != null);
            }
        } finally {
            Files.deleteIfExists(tempPath);
        }
    }
}
