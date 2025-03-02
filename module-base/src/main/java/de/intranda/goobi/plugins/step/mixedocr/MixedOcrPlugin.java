package de.intranda.goobi.plugins.step.mixedocr;

import com.google.gson.Gson;
import de.sub.goobi.config.ConfigPlugins;
import de.sub.goobi.helper.Helper;
import de.sub.goobi.helper.exceptions.DAOException;
import de.sub.goobi.helper.exceptions.SwapException;
import de.sub.goobi.persistence.managers.MetadataManager;
import lombok.extern.log4j.Log4j2;
import net.xeoh.plugins.base.annotations.PluginImplementation;
import org.apache.commons.configuration.SubnodeConfiguration;
import org.apache.commons.configuration.XMLConfiguration;
import org.apache.commons.configuration.reloading.FileChangedReloadingStrategy;
import org.apache.commons.configuration.tree.xpath.XPathExpressionEngine;
import org.apache.http.HttpResponse;
import org.apache.http.StatusLine;
import org.apache.http.client.fluent.Request;
import org.apache.http.entity.ContentType;
import org.goobi.beans.Step;
import org.goobi.production.enums.*;
import org.goobi.production.plugin.interfaces.IStepPluginVersion2;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.SQLException;
import java.util.HashMap;

@Log4j2
@PluginImplementation
public class MixedOcrPlugin implements IStepPluginVersion2 {
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
                Helper.addMessageToProcessJournal(step.getProzess().getId(), LogType.ERROR,
                        "Single page OCR configuration file does not exist. Please mark the pages using the 'OcrSelector' plugin.",
                        "Goobi OCR plugin");
                return PluginReturnValue.ERROR;
            }

            String callbackUrl = String.format("%s/plugins/ocr/%d/done", conf.getString("callbackBaseUrl"), jobId);

            String templateName = conf.getString("template");
            String sourceDir =
                    conf.getBoolean("useOrigDir") ? step.getProzess().getImagesOrigDirectory(false) : step.getProzess().getImagesTifDirectory(false);
            String language = String.join(",", MetadataManager.getAllMetadataValues(step.getProzess().getId(), "docLanguage"));
            ItmRequest antiquaReq = new ItmRequest(step.getProcessId().toString(), sourceDir, antiquaTargetDir.toString(), "antiqua", 10,
                    step.getId().toString(), step.getProzess().getTitel() + "_antiqua", templateName, "OCR", conf.getString("serverType"),
                    callbackUrl, step.getProzess().getTitel(), language, callbackUrl);
            ItmRequest fractureReq = new ItmRequest(step.getProcessId().toString(), sourceDir, fractureTargetDir.toString(), "fracture", 10,
                    step.getId().toString(), step.getProzess().getTitel() + "_fracture", templateName, "OCR", conf.getString("serverType"),
                    callbackUrl, step.getProzess().getTitel(), language, callbackUrl);
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
    public int getInterfaceVersion() {
        // TODO Auto-generated method stub
        return 0;
    }

}
