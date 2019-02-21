package de.intranda.goobi.plugins.step.mixedocr;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.sql.SQLException;
import java.util.HashMap;

import org.apache.commons.configuration.SubnodeConfiguration;
import org.apache.commons.configuration.XMLConfiguration;
import org.apache.commons.configuration.reloading.FileChangedReloadingStrategy;
import org.apache.commons.configuration.tree.xpath.XPathExpressionEngine;
import org.apache.http.client.fluent.Request;
import org.apache.http.entity.ContentType;
import org.goobi.beans.Step;
import org.goobi.production.enums.PluginGuiType;
import org.goobi.production.enums.PluginType;
import org.goobi.production.enums.StepReturnValue;
import org.goobi.production.plugin.interfaces.IRestGuiPlugin;

import com.google.gson.Gson;

import de.sub.goobi.config.ConfigPlugins;
import de.sub.goobi.helper.HelperSchritte;
import de.sub.goobi.helper.exceptions.DAOException;
import de.sub.goobi.helper.exceptions.SwapException;
import de.sub.goobi.persistence.managers.MetadataManager;
import de.sub.goobi.persistence.managers.StepManager;
import lombok.extern.log4j.Log4j;
import net.xeoh.plugins.base.annotations.PluginImplementation;
import spark.Service;

@Log4j
@PluginImplementation
public class MixedOcrPlugin implements IRestGuiPlugin {
    private static String title = "intranda_step_mixedocr";

    private Step step;

    @Override
    public void initialize(Step step, String returnPath) {
        this.step = step;

    }

    @Override
    public boolean execute() {
        // send two jobs to itm, one for antiqua, one for fracture. And add this step to this plugin's db table
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
        try {
            // first, we insert the job in the DB
            long jobId = MixedOcrDao.addJob(this.step.getId());
            String callbackUrl = String.format("%s/plugins/ocr/%d/done", conf.getString("callbackBaseUrl"), jobId);
            String antiquaTargetDir = Paths.get(step.getProzess().getProcessDataDirectory(), "ocr", "partial_" + jobId + "_antiqua").toString();
            String fractureTargetDir = Paths.get(step.getProzess().getProcessDataDirectory(), "ocr", "partial_" + jobId + "_fracture").toString();
            String templateName = conf.getString("template");
            String sourceDir = conf.getBoolean("useOrigDir") ? step.getProzess().getImagesOrigDirectory(false) : step.getProzess()
                    .getImagesTifDirectory(false);
            String language = String.join(",", MetadataManager.getAllMetadataValues(step.getProzess().getId(), "docLanguage"));
            ItmRequest antiquaReq = new ItmRequest(step.getProcessId().toString(), sourceDir, antiquaTargetDir, "antiqua", 10, step.getId()
                    .toString(), step.getProzess().getTitel(), templateName, "OCR", "", callbackUrl, step.getProzess().getTitel(), language);
            ItmRequest fractureReq = new ItmRequest(step.getProcessId().toString(), sourceDir, fractureTargetDir, "fracture", 10, step.getId()
                    .toString(), step.getProzess().getTitel(), templateName, "OCR", "", callbackUrl, step.getProzess().getTitel(), language);
            //send both jobs to itm
            Gson gson = new Gson();
            Request.Post(conf.getString("itmUrl"))
                    .bodyString(gson.toJson(antiquaReq), ContentType.APPLICATION_JSON)
                    .execute();
            Request.Post(conf.getString("itmUrl"))
                    .bodyString(gson.toJson(fractureReq), ContentType.APPLICATION_JSON)
                    .execute();
        } catch (SQLException e) {
            // TODO Add to goobi log
            log.error(e);
        } catch (IOException e) {
            // TODO Auto-generated catch block
            log.error(e);
        } catch (InterruptedException e) {
            // TODO Auto-generated catch block
            log.error(e);
        } catch (SwapException e) {
            // TODO Auto-generated catch block
            log.error(e);
        } catch (DAOException e) {
            // TODO Auto-generated catch block
            log.error(e);
        }
        return false;
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
                    mergeDirs(jobId, step);
                }
                HelperSchritte hs = new HelperSchritte();
                hs.CloseStepObjectAutomatic(step);
                return "";
            });
            http.get("/info", (req, res) -> {
                return "plugin " + title + " is loaded properly.\n";
            });
        });

    }

    private void mergeDirs(long jobId, Step step) {
        try {
            Path antiquaTargetDir = Paths.get(step.getProzess().getProcessDataDirectory(), "ocr", "partial_" + jobId + "_antiqua", "ocr");
            Path fractureTargetDir = Paths.get(step.getProzess().getProcessDataDirectory(), "ocr", "partial_" + jobId + "_fracture", "ocr");

            Path ocrDir = Paths.get(step.getProzess().getOcrDirectory());
            renameOldOcrDirs(ocrDir);

            copyToOcrDir(antiquaTargetDir, ocrDir);
            copyToOcrDir(fractureTargetDir, ocrDir);

        } catch (IOException e) {
            // TODO Auto-generated catch block
            log.error(e);
        } catch (InterruptedException e) {
            // TODO Auto-generated catch block
            log.error(e);
        } catch (SwapException e) {
            // TODO Auto-generated catch block
            log.error(e);
        } catch (DAOException e) {
            // TODO Auto-generated catch block
            log.error(e);
        }
    }

    private void renameOldOcrDirs(Path path) throws IOException {
        if (Files.exists(path)) {
            Path newPath = Paths.get(path.toString() + 1);
            if (Files.exists(newPath)) {
                renameOldOcrDirs(path, newPath, 1);
            }
            Files.move(path, newPath);
        }
    }

    private void renameOldOcrDirs(Path origPath, Path path, int i) throws IOException {
        i++;
        Path newPath = Paths.get(origPath.toString() + i);
        if (Files.exists(newPath)) {
            renameOldOcrDirs(origPath, newPath, i);
        }
        Files.move(path, newPath);
    }

    private void copyToOcrDir(Path sourceDir, Path ocrDir) throws IOException {
        try (DirectoryStream<Path> dirStream = Files.newDirectoryStream(sourceDir)) {
            for (Path p : dirStream) {
                Path targetDir = ocrDir.resolve(p.getFileName());
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

    @Override
    public String[] getJsPaths() {
        return new String[0];
    }

    @Override
    public void extractAssets(Path assetsPath) {
        // no assets
    }

}
