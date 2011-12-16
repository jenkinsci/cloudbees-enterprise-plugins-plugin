package com.cloudbees.jenkins.plugins.freeplugins;

import hudson.BulkChange;
import hudson.Extension;
import hudson.Plugin;
import hudson.PluginWrapper;
import hudson.init.InitMilestone;
import hudson.init.Initializer;
import hudson.model.Hudson;
import hudson.model.UpdateCenter;
import hudson.model.UpdateSite;
import hudson.util.PersistedList;
import hudson.util.TimeUnit2;
import jenkins.model.Jenkins;
import org.jvnet.hudson.reactor.Milestone;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;

public class PluginImpl extends Plugin {

    public static enum Milestones implements Milestone {
        UPDATE_CENTER_CONFIGURED
    }

    private static final Logger LOGGER = Logger.getLogger(PluginImpl.class.getName());

    private static final String CLOUDBEES_UPDATE_CENTER_URL =
            "http://nectar-updates.cloudbees.com/updateCenter/1.400/update-center.json";

    private static final Set<String> cloudBeesUpdateCenterUrls = new HashSet<String>(Arrays.asList(
            CLOUDBEES_UPDATE_CENTER_URL
    ));

    private static final String CLOUDBEES_UPDATE_CENTER_ID = "cloudbees";

    private static final Set<String> cloudBeesUpdateCenterIds = new HashSet<String>(Arrays.asList(
            CLOUDBEES_UPDATE_CENTER_ID
    ));

    private static final String[] CLOUDBEES_FREE_PLUGINS = {
            "cloudbees-credentials",
            "cloudbees-registration",
            "cloudbees-license",
            "free-license",
    };

    private static final List<String> pendingPluginInstalls = new ArrayList<String>();

    @Initializer(after = InitMilestone.EXTENSIONS_AUGMENTED, attains = "cloudbees-update-center-configured")
    public static void addUpdateCenter() throws Exception {
        boolean found = false;

        UpdateCenter updateCenter = Hudson.getInstance().getUpdateCenter();
        PersistedList<UpdateSite> sites = updateCenter.getSites();
        List<UpdateSite> forRemoval = new ArrayList<UpdateSite>();
        for (UpdateSite site : sites) {
            if (cloudBeesUpdateCenterUrls.contains(site.getUrl()) || cloudBeesUpdateCenterIds.contains(site.getId())) {
                boolean valid = site instanceof CloudBeesUpdateSite
                        && CLOUDBEES_UPDATE_CENTER_URL.equals(site.getUrl())
                        && CLOUDBEES_UPDATE_CENTER_ID.equals(site.getId());
                found = found || valid;
                if (!(valid)) {
                    forRemoval.add(site);
                }
            }
        }
        if (!found || !forRemoval.isEmpty()) {
            BulkChange bc = new BulkChange(updateCenter);
            try {
                for (UpdateSite site : forRemoval) {
                    LOGGER.info("Removing legacy CloudBees Update Centerf from list of update centers");
                    sites.remove(site);
                }
                if (sites.isEmpty()) {
                    LOGGER.info("Adding Default Update Center to list of update centers as it was missing");
                    sites.add(new UpdateSite("default", "http://updates.jenkins-ci.org/update-center.json"));
                }
                if (!found) {
                    LOGGER.info("Adding CloudBees Update Center to list of update centers");
                    sites.add(new CloudBeesUpdateSite(CLOUDBEES_UPDATE_CENTER_ID, CLOUDBEES_UPDATE_CENTER_URL));
                }
            } finally {
                bc.commit();
            }
        }
    }

    @Initializer(requires = "cloudbees-update-center-configured")
    public static void installCorePlugins() {
        Method deploy;
        try {
            deploy = UpdateSite.Plugin.class.getMethod("deploy", boolean.class);
            if (!deploy.isAccessible()) {
                deploy.setAccessible(true);
            }
        } catch (NoSuchMethodException e) {
            // ignore pre 1.442 jenkins
            deploy = null;
        } catch (SecurityException e) {
            // ignore pre 1.442 jenkins
            deploy = null;
        }
        boolean remainderPending = false;
        for (String pluginArtifactId : CLOUDBEES_FREE_PLUGINS) {
            PluginWrapper plugin = Hudson.getInstance().getPluginManager().getPlugin(pluginArtifactId);
            if (plugin == null) {
                UpdateSite.Plugin p =
                        remainderPending ? null : Jenkins.getInstance().getUpdateCenter().getPlugin(pluginArtifactId);
                if (p == null) {
                    synchronized (pendingPluginInstalls) {
                        pendingPluginInstalls.add(pluginArtifactId);
                        remainderPending = true;
                    }
                } else {
                    LOGGER.info("Installing CloudBees plugin: " + pluginArtifactId);
                    if (deploy != null) {
                        try {
                            deploy.invoke(p, true);
                            LOGGER.info("Dynamically Installed CloudBees plugin: " + pluginArtifactId);
                        } catch (Throwable e) {
                            synchronized (pendingPluginInstalls) {
                                pendingPluginInstalls.add(pluginArtifactId);
                            }
                        }
                    } else {
                        try {
                            p.deploy();
                            LOGGER.info("Installed CloudBees plugin: " + pluginArtifactId);
                        } catch (Throwable e) {
                            synchronized (pendingPluginInstalls) {
                                pendingPluginInstalls.add(pluginArtifactId);
                            }
                        }
                    }
                }
            }
        }
        synchronized (pendingPluginInstalls) {
            if (!pendingPluginInstalls.isEmpty()) {
                LOGGER.info("Starting background thread for core plugin installation");
                DelayedInstaller worker = new DelayedInstaller();
                worker.setDaemon(true);
                worker.start();
            }
        }
    }

    @Extension
    public static class DelayedInstaller extends Thread {

        @Override
        public void run() {
            long nextWarning = 0;
            try {
                Method deploy;
                try {
                    deploy = UpdateSite.Plugin.class.getMethod("deploy", boolean.class);
                    if (!deploy.isAccessible()) {
                        deploy.setAccessible(true);
                    }
                } catch (NoSuchMethodException e) {
                    // ignore pre 1.442 jenkins
                    deploy = null;
                } catch (SecurityException e) {
                    // ignore pre 1.442 jenkins
                    deploy = null;
                }
                boolean loop = true;
                while (loop) {
                    LOGGER.fine("Background thread for core plugin installation awake");
                    try {
                        synchronized (pendingPluginInstalls) {
                            while (loop = !pendingPluginInstalls.isEmpty()) {
                                String pluginArtifactId = pendingPluginInstalls.get(0);
                                UpdateSite.Plugin p =
                                        Jenkins.getInstance().getUpdateCenter().getPlugin(pluginArtifactId);
                                if (p == null) {
                                    if (System.currentTimeMillis() > nextWarning) {
                                        LOGGER.log(Level.WARNING,
                                                "Cannot find core plugin {0}, the CloudBees free plugins cannot be "
                                                        + "installed without this core plugin. Will try again later.",
                                                pluginArtifactId);
                                        nextWarning = System.currentTimeMillis() + TimeUnit2.HOURS.toMillis(1);
                                    }
                                    break;
                                } else {
                                    LOGGER.info("Installing CloudBees plugin: " + pluginArtifactId);
                                    if (deploy != null) {
                                        try {
                                            deploy.invoke(p, true);
                                            LOGGER.info("Dynamically Installed CloudBees plugin: " + pluginArtifactId);
                                            pendingPluginInstalls.remove(0);
                                        } catch (Throwable e) {
                                            // ignore
                                        }
                                    } else {
                                        try {
                                            p.deploy();
                                            LOGGER.info("Installed CloudBees plugin: " + pluginArtifactId);
                                            pendingPluginInstalls.remove(0);
                                        } catch (Throwable e) {
                                            // ignore
                                        }
                                    }
                                }
                            }
                        }
                        Thread.sleep(5000);
                    } catch (InterruptedException e) {
                        // ignore
                    } catch (Throwable t) {
                        try {
                            Thread.sleep(5000);
                        } catch (InterruptedException e) {
                            // ignore
                        }
                    }
                }
            } finally {
                LOGGER.info("Background thread for core plugin installation finished.");
            }
        }
    }

    static {
        UpdateCenter.XSTREAM.alias("cloudbees", CloudBeesUpdateSite.class);
    }
}
