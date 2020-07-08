package de.intranda.goobi.plugins.step.mixedocr;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.sql.SQLException;
import java.util.Arrays;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import org.apache.commons.configuration.SubnodeConfiguration;
import org.apache.commons.configuration.XMLConfiguration;
import org.apache.commons.configuration.reloading.FileChangedReloadingStrategy;
import org.apache.commons.configuration.tree.xpath.XPathExpressionEngine;
import org.apache.commons.io.FileUtils;
import org.apache.http.HttpResponse;
import org.apache.http.StatusLine;
import org.apache.http.client.fluent.Request;
import org.apache.http.entity.ContentType;
import org.goobi.beans.LogEntry;
import org.goobi.beans.Step;
import org.goobi.production.enums.LogType;
import org.goobi.production.enums.PluginGuiType;
import org.goobi.production.enums.PluginReturnValue;
import org.goobi.production.enums.PluginType;
import org.goobi.production.enums.StepReturnValue;
import org.goobi.production.plugin.interfaces.IRestGuiPlugin;
import org.goobi.production.plugin.interfaces.IStepPluginVersion2;
import org.jdom2.Attribute;
import org.jdom2.Element;
import org.jdom2.output.Format;
import org.jdom2.output.XMLOutputter;

import com.google.gson.Gson;

import de.intranda.digiverso.ocr.alto.model.structureclasses.Page;
import de.intranda.digiverso.ocr.alto.model.structureclasses.logical.AltoDocument;
import de.intranda.digiverso.ocr.alto.model.structureclasses.logical.Chapter;
import de.sub.goobi.config.ConfigPlugins;
import de.sub.goobi.helper.HelperSchritte;
import de.sub.goobi.helper.exceptions.DAOException;
import de.sub.goobi.helper.exceptions.SwapException;
import de.sub.goobi.persistence.managers.MetadataManager;
import de.sub.goobi.persistence.managers.ProcessManager;
import de.sub.goobi.persistence.managers.StepManager;
import lombok.extern.log4j.Log4j2;
import net.xeoh.plugins.base.annotations.PluginImplementation;
import spark.Service;

@Log4j2
@PluginImplementation
public class MixedOcrPlugin implements IRestGuiPlugin, IStepPluginVersion2 {
    private static String title = "intranda_step_mixedocr";

    private Step step;

    @Override
    public void initialize(Step step, String returnPath) {
        this.step = step;

    }

    @Override
    public PluginReturnValue run() {
        // send two jobs to itm, one for antiqua, one for fracture. And add this step to this plugin's db table
        SubnodeConfiguration conf = getConfig(step);
        try {
            // first, we insert the job in the DB
            long jobId = MixedOcrDao.addJob(this.step.getId());
            //then, we create all needed folders for OCR and copy information to them
            Path antiquaTargetDir = Paths.get(step.getProzess().getProcessDataDirectory(), "ocr_partial_" + jobId, "antiqua");
            Path fractureTargetDir = Paths.get(step.getProzess().getProcessDataDirectory(), "ocr_partial_" + jobId, "fracture");
            Files.createDirectories(antiquaTargetDir);
            Files.createDirectories(fractureTargetDir);
            Path ocrSelectedFile = Paths.get(step.getProzess().getProcessDataDirectory(), "ocrPages.json");
            if (Files.exists(ocrSelectedFile)) {
                Files.copy(ocrSelectedFile, antiquaTargetDir.resolve(ocrSelectedFile.getFileName()));
                Files.copy(ocrSelectedFile, fractureTargetDir.resolve(ocrSelectedFile.getFileName()));
            } else {
                LogEntry le = new LogEntry();
                le.setProcessId(step.getProzess().getId());
                le.setContent("Single page OCR configuration file does not exist. Please mark the pages using the 'OcrSelector' plugin.");
                le.setType(LogType.ERROR);
                le.setUserName("Goobi OCR plugin");
                le.setCreationDate(new Date());

                ProcessManager.saveLogEntry(le);

                return PluginReturnValue.ERROR;
            }

            String callbackUrl = String.format("%s/plugins/ocr/%d/done", conf.getString("callbackBaseUrl"), jobId);

            String templateName = conf.getString("template");
            String sourceDir = conf.getBoolean("useOrigDir") ? step.getProzess().getImagesOrigDirectory(false) : step.getProzess()
                    .getImagesTifDirectory(false);
            String language = String.join(",", MetadataManager.getAllMetadataValues(step.getProzess().getId(), "docLanguage"));
            ItmRequest antiquaReq = new ItmRequest(step.getProcessId().toString(), sourceDir, antiquaTargetDir.toString(), "antiqua", 10, step.getId()
                    .toString(), step.getProzess().getTitel() + "_antiqua", templateName, "OCR", conf.getString("serverType"), callbackUrl,
                    step
                            .getProzess()
                            .getTitel(),
                    language, callbackUrl);
            ItmRequest fractureReq = new ItmRequest(step.getProcessId().toString(), sourceDir, fractureTargetDir.toString(), "fracture", 10, step
                    .getId()
                    .toString(), step.getProzess().getTitel() + "_fracture", templateName, "OCR", conf.getString("serverType"), callbackUrl,
                    step.getProzess().getTitel(), language, callbackUrl);
            //send both jobs to itm
            Gson gson = new Gson();
            HttpResponse antiquaResp = Request.Post(conf.getString("itmUrl"))
                    .bodyString(gson.toJson(antiquaReq), ContentType.APPLICATION_JSON)
                    .execute()
                    .returnResponse();
            StatusLine line = antiquaResp.getStatusLine();
            if (line.getStatusCode() >= 400) {
                ByteArrayOutputStream baos = new ByteArrayOutputStream();
                antiquaResp.getEntity().writeTo(baos);
                log.error(String.format("antiqua request to itm was unsucsessful. HTTP status code %d. Respone body:\n%s", line.getStatusCode(),
                        new String(baos.toByteArray())));
                return PluginReturnValue.ERROR;
            }
            HttpResponse fractureResp = Request.Post(conf.getString("itmUrl"))
                    .bodyString(gson.toJson(fractureReq), ContentType.APPLICATION_JSON)
                    .execute()
                    .returnResponse();
            line = fractureResp.getStatusLine();
            if (line.getStatusCode() >= 400) {
                ByteArrayOutputStream baos = new ByteArrayOutputStream();
                fractureResp.getEntity().writeTo(baos);
                log.error(String.format("fracture request to itm was unsucsessful. HTTP status code %d. Respone body:\n%s", line.getStatusCode(),
                        new String(baos.toByteArray())));
                return PluginReturnValue.ERROR;
            }
        } catch (SQLException e) {
            // TODO Add to goobi log
            log.error(e);
            return PluginReturnValue.ERROR;
        } catch (IOException e) {
            // TODO Auto-generated catch block
            log.error(e);
            return PluginReturnValue.ERROR;
        } catch (InterruptedException e) {
            // TODO Auto-generated catch block
            log.error(e);
            return PluginReturnValue.ERROR;
        } catch (SwapException e) {
            // TODO Auto-generated catch block
            log.error(e);
            return PluginReturnValue.ERROR;
        } catch (DAOException e) {
            // TODO Auto-generated catch block
            log.error(e);
            return PluginReturnValue.ERROR;
        }
        return PluginReturnValue.WAIT;
    }

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
            conf = xmlConfig
                    .configurationAt("//config[./project = '" + projectName + "'][./step = '" + step.getTitel() + "']");
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

    @Override
    public boolean execute() {
        PluginReturnValue ret = this.run();
        if (ret == PluginReturnValue.ERROR) {
            return false;
        }
        return true;
    }

    @Override
    public String cancel() {
        return null;
    }

    @Override
    public String finish() {
        return null;
    }

    @Override
    public HashMap<String, StepReturnValue> validate() {
        return null;
    }

    @Override
    public Step getStep() {
        return this.step;
    }

    @Override
    public PluginGuiType getPluginGuiType() {
        return PluginGuiType.NONE;
    }

    @Override
    public String getPagePath() {
        return null;
    }

    @Override
    public PluginType getType() {
        return PluginType.Step;
    }

    @Override
    public String getTitle() {
        return title;
    }

    @Override
    public void initRoutes(Service http) {
        http.path("/ocr", () -> {
            http.post("/:jobId/done", (req, res) -> {
                // write to db and check if stuff is done, then merge the two folders
                long jobId = Long.parseLong(req.params("jobId"));
                boolean fracture = req.headers("jobType").equals("fracture");
                MixedOcrDao.setJobDone(jobId, fracture);
                if (MixedOcrDao.isJobDone(jobId)) {
                    int stepId = MixedOcrDao.getStepIdForJob(jobId);
                    Step step = StepManager.getStepById(stepId);
                    HelperSchritte hs = new HelperSchritte();
                    if (mergeDirs(jobId, step) && fillMissingAlto(step, getConfig(step))) {
                        hs.CloseStepObjectAutomatic(step);
                    } else {
                        LogEntry le = new LogEntry();
                        le.setProcessId(step.getProzess().getId());
                        le.setContent("Error merging OCR results");
                        le.setType(LogType.ERROR);
                        le.setUserName("Goobi OCR plugin");
                        le.setCreationDate(new Date());

                        ProcessManager.saveLogEntry(le);
                        hs.errorStep(step);
                    }
                }
                return "";
            });
            http.get("/info", (req, res) -> {
                return "plugin " + title + " is loaded properly.\n";
            });
        });

    }

    private boolean fillMissingAlto(Step step, SubnodeConfiguration conf) {
        try {
            Path altoDir = Paths.get(step.getProzess().getOcrAltoDirectory());
            if (!Files.exists(altoDir)) {
                // maybe there is no alto needed here...
                log.warn("no alto dir found. Skip adding missing alto!");
                return true;
            }
            final Set<String> altoNames = new HashSet<>(Arrays.asList(altoDir.toFile().list()));
            String sourceDirStr = conf.getBoolean("useOrigDir") ? step.getProzess().getImagesOrigDirectory(false) : step.getProzess()
                    .getImagesTifDirectory(false);
            Path sourceDir = Paths.get(sourceDirStr);
            String[] sourceFiles = sourceDir.toFile().list();
            List<String> missingAlto = filterMissingAlto(altoNames, sourceFiles);
            for (String altoName : missingAlto) {
                createEmptyAlto(altoDir.resolve(altoName));
            }
        } catch (SwapException | DAOException | IOException | InterruptedException e) {
            log.error(e);
            return false;
        }
        return true;
    }

    public static void createEmptyAlto(Path altoPath) throws IOException {
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
        })
                .filter(name -> !altoNames.contains(name))
                .collect(Collectors.toList());
    }

    private boolean mergeDirs(long jobId, Step step) {
        try {
            Path antiquaTargetDir = Paths.get(step.getProzess().getProcessDataDirectory(), "ocr_partial_" + jobId, "antiqua", "ocr");
            Path fractureTargetDir = Paths.get(step.getProzess().getProcessDataDirectory(), "ocr_partial_" + jobId, "fracture", "ocr");

            Path ocrDir = Paths.get(step.getProzess().getOcrDirectory());
            renameOldOcrDirs(ocrDir);

            if (Files.exists(antiquaTargetDir)) {
                copyToOcrDir(antiquaTargetDir, ocrDir, step.getProzess().getTitel());
            }
            if (Files.exists(fractureTargetDir)) {
                copyToOcrDir(fractureTargetDir, ocrDir, step.getProzess().getTitel());
            }
            Path delP = Paths.get(step.getProzess().getProcessDataDirectory(), "ocr_partial_" + jobId);
            if (Files.exists(delP) && Files.isDirectory(delP) && delP.getFileName().toString().startsWith("ocr_par")) {
                FileUtils.deleteQuietly(delP.toFile());
            }

        } catch (IOException e) {
            // TODO Auto-generated catch block
            log.error(e);
            return false;
        } catch (InterruptedException e) {
            // TODO Auto-generated catch block
            log.error(e);
            return false;
        } catch (SwapException e) {
            // TODO Auto-generated catch block
            log.error(e);
            return false;
        } catch (DAOException e) {
            // TODO Auto-generated catch block
            log.error(e);
            return false;
        }
        return true;
    }

    private void renameOldOcrDirs(Path path) throws IOException {
        if (Files.exists(path)) {
            Path newPath = Paths.get(path.toString() + 1);
            if (Files.exists(newPath)) {
                renameOldOcrDirs(path, newPath, 1);
            }
            Files.move(path, newPath);
        }
        Files.createDirectories(path);
    }

    private void renameOldOcrDirs(Path origPath, Path path, int i) throws IOException {
        i++;
        Path newPath = Paths.get(origPath.toString() + i);
        if (Files.exists(newPath)) {
            renameOldOcrDirs(origPath, newPath, i);
        }
        Files.move(path, newPath);
    }

    private void copyToOcrDir(Path sourceDir, Path ocrDir, String processTitle) throws IOException {
        try (DirectoryStream<Path> dirStream = Files.newDirectoryStream(sourceDir)) {
            for (Path p : dirStream) {
                String targetName = getTargetName(p.getFileName().toString(), processTitle);
                Path targetDir = ocrDir.resolve(targetName);
                if (!Files.exists(targetDir)) {
                    Files.createDirectories(targetDir);
                }
                try (DirectoryStream<Path> innerStream = Files.newDirectoryStream(p)) {
                    for (Path copyFile : innerStream) {
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

    @Override
    public String[] getJsPaths() {
        return new String[0];
    }

    @Override
    public void extractAssets(Path assetsPath) {
        // no assets
    }

    @Override
    public int getInterfaceVersion() {
        // TODO Auto-generated method stub
        return 0;
    }

}
