package org.goobi.api.rest;

import de.intranda.digiverso.ocr.alto.model.structureclasses.Page;
import de.intranda.digiverso.ocr.alto.model.structureclasses.logical.AltoDocument;
import de.intranda.digiverso.ocr.alto.model.structureclasses.logical.Chapter;
import de.intranda.goobi.plugins.step.mixedocr.MixedOcrDao;
import de.sub.goobi.config.ConfigPlugins;
import de.sub.goobi.helper.Helper;
import de.sub.goobi.helper.HelperSchritte;
import de.sub.goobi.helper.exceptions.DAOException;
import de.sub.goobi.helper.exceptions.SwapException;
import de.sub.goobi.persistence.managers.StepManager;
import jakarta.ws.rs.HeaderParam;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.core.Response;
import lombok.extern.log4j.Log4j2;
import org.apache.commons.configuration.SubnodeConfiguration;
import org.apache.commons.configuration.XMLConfiguration;
import org.apache.commons.configuration.reloading.FileChangedReloadingStrategy;
import org.apache.commons.configuration.tree.xpath.XPathExpressionEngine;
import org.apache.commons.io.FileUtils;
import org.goobi.beans.Step;
import org.goobi.production.enums.LogType;
import org.jdom2.Attribute;
import org.jdom2.Element;
import org.jdom2.output.Format;
import org.jdom2.output.XMLOutputter;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.sql.SQLException;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

@Log4j2
@Path("/plugins/ocr")
public class MixedOcrPluginApi {
    // TODO: Duplicate
    private static final String title = "intranda_step_mixedocr";

    @POST
    @Path("/{jobid}/done")
    public Response finishJob(@PathParam("jobid") long jobId, @HeaderParam("jobType") String jobType) throws SQLException {
        // write to db and check if stuff is done, then merge the two folders
        boolean fracture = "fracture".equals(jobType);
        MixedOcrDao.setJobDone(jobId, fracture);
        if (MixedOcrDao.isJobDone(jobId)) {
            int stepId = MixedOcrDao.getStepIdForJob(jobId);
            Step step = StepManager.getStepById(stepId);
            HelperSchritte hs = new HelperSchritte();
            if (mergeDirs(jobId, step) && fillMissingAlto(step, getConfig(step))) {
                hs.CloseStepObjectAutomatic(step);
            } else {
                Helper.addMessageToProcessJournal(step.getProzess().getId(), LogType.ERROR,
                        "Error merging OCR results",
                        "Goobi OCR plugin");
                hs.errorStep(step);
            }
        }
        return Response.ok().build();
    }

    // TODO: Duplicate logic
    public SubnodeConfiguration getConfig(Step step) {
        String projectName = step.getProzess().getProjekt().getTitel();
        XMLConfiguration xmlConfig = ConfigPlugins.getPluginConfig(title);
        xmlConfig.setExpressionEngine(new XPathExpressionEngine());
        xmlConfig.setReloadingStrategy(new FileChangedReloadingStrategy());

        SubnodeConfiguration conf = null;

        // order of configuration is:
        // 1.) project name and step name matches
        // 2.) step name matches and project is *
        // 3.) project name matches and step name is *
        // 4.) project name and step name are *
        try {
            conf = xmlConfig.configurationAt("//config[./project = '" + projectName + "'][./step = '" + step.getTitel() + "']");
        } catch (IllegalArgumentException e) {
            try {
                conf = xmlConfig.configurationAt("//config[./project = '*'][./step = '" + step.getTitel() + "']");
            } catch (IllegalArgumentException e1) {
                try {
                    conf = xmlConfig.configurationAt("//config[./project = '" + projectName + "'][./step = '*']");
                } catch (IllegalArgumentException e2) {
                    conf = xmlConfig.configurationAt("//config[./project = '*'][./step = '*']");
                }
            }
        }
        return conf;
    }

    private boolean fillMissingAlto(Step step, SubnodeConfiguration conf) {
        try {
            java.nio.file.Path altoDir = Paths.get(step.getProzess().getOcrAltoDirectory());
            if (!Files.exists(altoDir)) {
                // maybe there is no alto needed here...
                log.warn("no alto dir found. Skip adding missing alto!");
                return true;
            }
            final Set<String> altoNames = new HashSet<>(Arrays.asList(altoDir.toFile().list()));
            String sourceDirStr =
                    conf.getBoolean("useOrigDir") ? step.getProzess().getImagesOrigDirectory(false) : step.getProzess().getImagesTifDirectory(false);
            java.nio.file.Path sourceDir = Paths.get(sourceDirStr);
            String[] sourceFiles = sourceDir.toFile().list();
            List<String> missingAlto = filterMissingAlto(altoNames, sourceFiles);
            for (String altoName : missingAlto) {
                createEmptyAlto(altoDir.resolve(altoName));
            }
        } catch (SwapException | DAOException | IOException e) {
            log.error(e);
            return false;
        }
        return true;
    }

    private boolean mergeDirs(long jobId, Step step) {
        try {
            java.nio.file.Path antiquaTargetDir = Paths.get(step.getProzess().getProcessDataDirectory(), "ocr_partial_" + jobId, "antiqua", "ocr");
            java.nio.file.Path fractureTargetDir = Paths.get(step.getProzess().getProcessDataDirectory(), "ocr_partial_" + jobId, "fracture", "ocr");

            java.nio.file.Path ocrDir = Paths.get(step.getProzess().getOcrDirectory());
            renameOldOcrDirs(ocrDir);

            if (Files.exists(antiquaTargetDir)) {
                copyToOcrDir(antiquaTargetDir, ocrDir, step.getProzess().getTitel());
            }
            if (Files.exists(fractureTargetDir)) {
                copyToOcrDir(fractureTargetDir, ocrDir, step.getProzess().getTitel());
            }
            java.nio.file.Path delP = Paths.get(step.getProzess().getProcessDataDirectory(), "ocr_partial_" + jobId);
            if (Files.exists(delP) && Files.isDirectory(delP) && delP.getFileName().toString().startsWith("ocr_par")) {
                FileUtils.deleteQuietly(delP.toFile());
            }

        } catch (IOException e) {
            // TODO Auto-generated catch block
            log.error(e);
            return false;
        } catch (SwapException e) {
            // TODO Auto-generated catch block
            log.error(e);
            return false;
        }
        return true;
    }

    private void renameOldOcrDirs(java.nio.file.Path path) throws IOException {
        if (Files.exists(path)) {
            java.nio.file.Path newPath = Paths.get(path.toString() + 1);
            if (Files.exists(newPath)) {
                renameOldOcrDirs(path, newPath, 1);
            }
            Files.move(path, newPath);
        }
        Files.createDirectories(path);
    }

    private void renameOldOcrDirs(java.nio.file.Path origPath, java.nio.file.Path path, int i) throws IOException {
        i++;
        java.nio.file.Path newPath = Paths.get(origPath.toString() + i);
        if (Files.exists(newPath)) {
            renameOldOcrDirs(origPath, newPath, i);
        }
        Files.move(path, newPath);
    }

    private void copyToOcrDir(java.nio.file.Path sourceDir, java.nio.file.Path ocrDir, String processTitle) throws IOException {
        try (DirectoryStream<java.nio.file.Path> dirStream = Files.newDirectoryStream(sourceDir)) {
            for (java.nio.file.Path p : dirStream) {
                String targetName = getTargetName(p.getFileName().toString(), processTitle);
                java.nio.file.Path targetDir = ocrDir.resolve(targetName);
                if (!Files.exists(targetDir)) {
                    Files.createDirectories(targetDir);
                }
                try (DirectoryStream<java.nio.file.Path> innerStream = Files.newDirectoryStream(p)) {
                    for (java.nio.file.Path copyFile : innerStream) {
                        Files.copy(copyFile, targetDir.resolve(copyFile.getFileName()), StandardCopyOption.REPLACE_EXISTING);
                    }
                }
            }
        }
    }

    private String getTargetName(String sourceFolder, String processTitle) {
        String[] split = sourceFolder.split("_");
        return processTitle + "_" + split[split.length - 1];
    }

    public static void createEmptyAlto(java.nio.file.Path altoPath) throws IOException {
        AltoDocument emptyAlto = new AltoDocument();
        Chapter chap = new Chapter();
        emptyAlto.addChild(chap);
        Page p = new Page();
        p.addAdditionalAttribute(new Attribute("PHYSICAL_IMG_NR", "1"));
        chap.addChild(p);
        Element el = emptyAlto.writeToDom();
        XMLOutputter xmlOut = new XMLOutputter(Format.getPrettyFormat());
        try (OutputStream out = Files.newOutputStream(altoPath)) {
            xmlOut.output(el, out);
        }
    }

    public static List<String> filterMissingAlto(Set<String> altoNames, String[] sourceFiles) {
        return Arrays.stream(sourceFiles).map(name -> {
            return name.substring(0, name.lastIndexOf('.')) + ".xml";
        }).filter(name -> !altoNames.contains(name)).collect(Collectors.toList());
    }
}
