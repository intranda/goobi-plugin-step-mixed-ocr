package de.intranda.goobi.plugins.step.mixedocr;

import java.nio.file.Path;
import java.util.HashMap;

import org.goobi.beans.Step;
import org.goobi.production.enums.PluginGuiType;
import org.goobi.production.enums.PluginType;
import org.goobi.production.enums.StepReturnValue;
import org.goobi.production.plugin.interfaces.IRestGuiPlugin;

import spark.Service;

public class MixedOcrPlugin implements IRestGuiPlugin {
    private static String title;

    private Step step;

    public void initialize(Step step, String returnPath) {
        this.step = step;

    }

    public boolean execute() {
        // TODO send two jobs to itm, one for antiqua, one for fracture. 
        return false;
    }

    public String cancel() {
        // TODO Auto-generated method stub
        return null;
    }

    public String finish() {
        // TODO Auto-generated method stub
        return null;
    }

    public HashMap<String, StepReturnValue> validate() {
        // TODO Auto-generated method stub
        return null;
    }

    public Step getStep() {
        return this.step;
    }

    public PluginGuiType getPluginGuiType() {
        return PluginGuiType.NONE;
    }

    public String getPagePath() {
        return null;
    }

    public PluginType getType() {
        return PluginType.Step;
    }

    public String getTitle() {
        return title;
    }

    public void initRoutes(Service http) {
        // TODO add routes to handle 

    }

    public String[] getJsPaths() {
        return new String[0];
    }

    public void extractAssets(Path assetsPath) {
        // no assets
    }

}
