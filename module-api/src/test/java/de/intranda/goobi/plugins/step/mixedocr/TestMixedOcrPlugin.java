package de.intranda.goobi.plugins.step.mixedocr;

import org.goobi.api.rest.MixedOcrPluginApi;
import org.jdom2.Document;
import org.jdom2.JDOMException;
import org.jdom2.input.SAXBuilder;
import org.junit.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.Assert.assertTrue;

public class TestMixedOcrPlugin {
    @Test
    public void testFilterMissingAlto() {
        Set<String> altoNames = new HashSet<>(Arrays.asList(new String[] { "00000001.xml", "00000002.xml", "00000003.xml" }));
        String[] imageNames = new String[] { "00000001.tif", "00000002.tif", "00000002.tif", "00000001_Redacted.tif" };
        List<String> missing = MixedOcrPluginApi.filterMissingAlto(altoNames, imageNames);
        assertTrue(missing.contains("00000001_Redacted.xml"));
    }

    @Test
    public void testCreateEmptyAlto() throws IOException, JDOMException {
        Path tempPath = Files.createTempFile("mixedocr_emptyalto_", ".xml");
        try {
            MixedOcrPluginApi.createEmptyAlto(tempPath);
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
