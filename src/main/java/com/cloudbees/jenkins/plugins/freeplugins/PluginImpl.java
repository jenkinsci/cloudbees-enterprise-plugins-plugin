package com.cloudbees.jenkins.plugins.freeplugins;

import hudson.BulkChange;
import hudson.Extension;
import hudson.Plugin;
import hudson.PluginWrapper;
import hudson.init.InitMilestone;
import hudson.init.Initializer;
import hudson.lifecycle.RestartNotSupportedException;
import hudson.model.Hudson;
import hudson.model.UpdateCenter;
import hudson.model.UpdateSite;
import hudson.util.PersistedList;
import hudson.util.TimeUnit2;
import jenkins.model.Jenkins;
import org.jvnet.hudson.reactor.Milestone;
import org.jvnet.localizer.Localizable;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Installs the custom update site.
 */
public class PluginImpl extends Plugin {

    public static enum Milestones implements Milestone {
        UPDATE_CENTER_CONFIGURED
    }

    /**
     * Our logger.
     */
    private static final Logger LOGGER = Logger.getLogger(PluginImpl.class.getName());

    private static final String CLOUDBEES_UPDATE_CENTER_URL =
            "http://jenkins-updates.cloudbees.com/update-center.json";

    private static final Set<String> cloudBeesUpdateCenterUrls = new HashSet<String>(Arrays.asList(
            CLOUDBEES_UPDATE_CENTER_URL,
            "http://nectar-updates.cloudbees.com/updateCenter/1.424/update-center.json",
            "http://nectar-updates.cloudbees.com/updateCenter/1.400/update-center.json",
            "http://nectar-updates.cloudbees.com/update-center.json"
    ));

    private static final String CLOUDBEES_UPDATE_CENTER_ID = "cloudbees.proprietary";

    private static final Set<String> cloudBeesUpdateCenterIds = new HashSet<String>(Arrays.asList(
            CLOUDBEES_UPDATE_CENTER_ID,
            "ichci"
    ));

    private static final String[] CLOUDBEES_FREE_PLUGINS = {
            "free-license",
    };

    private static final List<String> pendingPluginInstalls = new ArrayList<String>();

    /**
     * Guarded by {@link #pendingPluginInstalls}.
     */
    private static DelayedInstaller worker = null;

    private static volatile Localizable status = null;

    public static Localizable getStatus() {
        return status;
    }

    @Initializer(after = InitMilestone.EXTENSIONS_AUGMENTED, attains = "cloudbees-update-center-configured")
    public static void addUpdateCenter() throws Exception {
        UpdateCenter updateCenter = Hudson.getInstance().getUpdateCenter();
        PersistedList<UpdateSite> sites = updateCenter.getSites();
        if (sites.isEmpty()) {
            // likely the list has not been loaded yet
            updateCenter.load();
            sites = updateCenter.getSites();
        }

        boolean found = false;
        List<UpdateSite> forRemoval = new ArrayList<UpdateSite>();
        for (UpdateSite site : sites) {
            LOGGER.log(Level.FINEST, "Update site {0} class {1} url {2}",
                    new Object[]{site.getId(), site.getClass(), site.getUrl()});
            if (cloudBeesUpdateCenterUrls.contains(site.getUrl()) || cloudBeesUpdateCenterIds.contains(site.getId())
                    || site instanceof CloudBeesUpdateSite) {
                LOGGER.log(Level.FINE, "Found possible match:\n  class = {0}\n  url = {1}\n  id = {2}",
                        new Object[]{site.getClass().getName(), site.getUrl(), site.getId()});
                boolean valid = site instanceof CloudBeesUpdateSite
                        && CLOUDBEES_UPDATE_CENTER_URL.equals(site.getUrl())
                        && CLOUDBEES_UPDATE_CENTER_ID.equals(site.getId());
                if (found || !valid) {
                    // remove old and duplicate entries
                    forRemoval.add(site);
                }
                found = found || valid;
            }
        }

        // now make the changes if we have any to make
        LOGGER.log(Level.FINE, "Found={0}\nRemoving={1}", new Object[]{found, forRemoval});
        if (!found || !forRemoval.isEmpty()) {
            BulkChange bc = new BulkChange(updateCenter);
            try {
                for (UpdateSite site : forRemoval) {
                    LOGGER.info("Removing legacy CloudBees Update Center from list of update centers");
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
        synchronized (pendingPluginInstalls) {
            if (!pendingPluginInstalls.isEmpty() && (worker == null || !worker.isAlive())) {
                status = Messages._Notice_downloadUCMetadata();
                LOGGER.info("Starting background thread for core plugin installation");
                worker = new DelayedInstaller();
                worker.setDaemon(true);
                worker.start();
            }
        }
    }

    @Extension
    public static class DelayedInstaller extends Thread {

        private long nextWarning;

        @Override
        public void run() {
            nextWarning = 0;
            try {
                boolean loop = true;
                while (loop) {
                    LOGGER.fine("Background thread for core plugin installation awake");
                    try {
                        UpdateSite cloudbeesSite =
                                Jenkins.getInstance().getUpdateCenter().getSite(CLOUDBEES_UPDATE_CENTER_ID);
                        if (cloudbeesSite.getDataTimestamp() > -1) {
                            loop = progressPluginInstalls(cloudbeesSite);
                        } else {
                            status = Messages._Notice_downloadUCMetadata();
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
                if (!loop) {
                    try {
                        status = Messages._Notice_scheduledRestart();
                        Jenkins.getInstance().safeRestart();
                    } catch (RestartNotSupportedException exception) {
                        // ignore if restart is not allowed
                        status = Messages._Notice_restartRequired();
                    }
                }
            } finally {
                LOGGER.info("Background thread for core plugin installation finished.");
                synchronized (pendingPluginInstalls) {
                    if (worker == this) {
                        worker = null;
                    }
                }
            }
        }

        private boolean progressPluginInstalls(UpdateSite cloudbeesSite) {
            synchronized (pendingPluginInstalls) {
                while (!pendingPluginInstalls.isEmpty()) {
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
                    } else if (p.getInstalled() != null && p.getInstalled().isEnabled()) {
                        LOGGER.info("Detected previous installation of CloudBees plugin: " + pluginArtifactId);
                        pendingPluginInstalls.remove(0);
                        nextWarning = 0;
                    } else {
                        LOGGER.info("Installing CloudBees plugin: " + pluginArtifactId);
                        status = Messages._Notice_installingPlugin();
                        try {
                            p.deploy().get();
                            LOGGER.info("Installed CloudBees plugin: " + pluginArtifactId);
                            pendingPluginInstalls.remove(0);
                            nextWarning = 0;
                        } catch (Throwable e) {
                            // ignore
                        }
                    }
                }
                return !pendingPluginInstalls.isEmpty();
            }
        }
    }

    static {
        UpdateCenter.XSTREAM.alias("cloudbees", CloudBeesUpdateSite.class);
    }
}
