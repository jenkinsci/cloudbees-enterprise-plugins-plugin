/*
 * The MIT License
 *
 * Copyright (c) 2011-2013, CloudBees, Inc., Stephen Connolly.
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 */
package com.cloudbees.jenkins.plugins.enterpriseplugins;

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
import hudson.security.ACL;
import hudson.triggers.SafeTimerTask;
import hudson.triggers.Trigger;
import hudson.util.PersistedList;
import hudson.util.TimeUnit2;
import hudson.util.VersionNumber;
import jenkins.model.Jenkins;
import org.acegisecurity.Authentication;
import org.acegisecurity.context.SecurityContextHolder;
import org.jvnet.hudson.reactor.Milestone;
import org.jvnet.localizer.Localizable;

import java.io.IOException;
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

    /**
     * The current update center URL.
     */
    private static final String CLOUDBEES_UPDATE_CENTER_URL =
            "http://jenkins-updates.cloudbees.com/update-center.json";

    /**
     * The current update center URL and any previous URLs that were used for the same content and should be migrated
     * to the current one.
     */
    private static final Set<String> cloudBeesUpdateCenterUrls = new HashSet<String>(Arrays.asList(
            CLOUDBEES_UPDATE_CENTER_URL,
            "http://nectar-updates.cloudbees.com/update-center.json"
    ));

    /**
     * The current update center ID.
     */
    private static final String CLOUDBEES_UPDATE_CENTER_ID = "jenkins-enterprise";

    /**
     * The current update center ID and any previous IDs that were used for the same content and should be migrated
     * to the current one.
     */
    private static final Set<String> cloudBeesUpdateCenterIds = new HashSet<String>(Arrays.asList(
            CLOUDBEES_UPDATE_CENTER_ID,
            "ichci"
    ));

    /**
     * The plugins that can and/or should be installed/upgraded.
     */
    private static final Dependency[] CLOUDBEES_FREE_PLUGINS = {
            require("cloudbees-license", "4.0"), // put this first
            require("cloudbees-support", "1.0"), // put this second
            require("active-directory", "1.33"),
            require("build-timeout", "1.11"),
            require("copyartifact", "1.27"),
            require("dashboard-view", "2.5"),
            require("parameterized-trigger", "2.17"),
            require("promoted-builds", "2.10"),
            require("translation", "1.10"),
            require("async-http-client", "1.7.8"),
            require("credentials", "1.4"),
            require("git", "1.3.0"),
            require("git-client", "1.0.6"),
            require("analysis-core", "1.49"),
            require("findbugs", "4.48"),
            require("mercurial", "1.45"),
            require("monitoring", "1.44.0"),
            require("build-view-column", "0.1"),
            require("warnings", "4.23"),
            require("infradna-backup", "3.5"),
            require("nectar-vmware", "3.10"),
            require("nectar-rbac", "3.8"),
            require("cloudbees-folder", "3.6"),
            require("cloudbees-folders-plus", "1.2"),
            require("wikitext", "3.2"),
            require("nectar-license", "4.0"),
            optional("free-license", "4.0"),
            require("cloudbees-template", "3.11"),
            require("skip-plugin", "3.4"),
            require("cloudbees-even-scheduler", "3.2"),
            require("cloudbees-update-center-plugin", "4.2"),
            require("cloudbees-secure-copy", "3.4"),
            require("cloudbees-wasted-minutes-tracker", "3.5"),
            require("git-validated-merge", "3.8"),
            require("cloudbees-jsync-archiver", "4.0"),
            require("cloudbees-ha"),
            require("cloudbees-label-throttling-plugin", "3.2"),
            require("cloudbees-plugin-usage", "1.0"),
            require("cloudbees-nodes-plus", "1.3"),
            require("cloudbees-aborted-builds", "1.1"),
            require("cloudbees-consolidated-build-view", "1.1")
    };

    /**
     * The list of plugin installations that remains to be completed.
     * <p/>
     * Guarded by {@link #pendingPluginInstalls}.
     */
    private static final List<Dependency> pendingPluginInstalls = new ArrayList<Dependency>();

    /**
     * Guarded by {@link #pendingPluginInstalls}.
     */
    private static DelayedInstaller worker = null;

    /**
     * The current status.
     */
    private static volatile Localizable status = null;

    /**
     * Whether the current status is important.
     */
    private static volatile boolean statusImportant = false;

    /**
     * The most recently installed version of this plugin, used to trigger whether to re-evaluate installing/upgrading
     * the {@link #CLOUDBEES_FREE_PLUGINS}.
     */
    private String installedVersion = null;

    public PluginImpl() {
    }

    @Override
    public void start() throws Exception {
        LOGGER.log(Level.INFO, "Started...");
        try {
            load();
        } catch (Throwable e) {
            LOGGER.log(Level.WARNING, "Could not deserialize state, assuming the plugins need re-installation", e);
            installedVersion = null;
        }
    }

    public boolean isInstalled() {
        if (installedVersion == null) {
            return false;
        }
        try {
            PluginWrapper pluginWrapper = Hudson.getInstance().getPluginManager().getPlugin(getClass());
            String targetVersion = getVersionString(pluginWrapper);
            LOGGER.log(Level.FINE, "Installed version = {0}. Target version = {1}",
                    new Object[]{installedVersion, targetVersion});
            return !new VersionNumber(installedVersion).isOlderThan(new VersionNumber(targetVersion));
        } catch (Throwable t) {
            // if in doubt, it's not installed
            return false;
        }
    }

    public void setInstalled(boolean installed) {
        boolean changed = false;
        if (installed) {
            PluginWrapper pluginWrapper = Hudson.getInstance().getPluginManager().getPlugin(getClass());
            String version = getVersionString(pluginWrapper);
            if (!version.equals(installedVersion)) {
                this.installedVersion = version;
                changed = true;
            }
        } else {
            if (installedVersion != null) {
                installedVersion = null;
                changed = true;
            }
        }
        if (changed) {
            try {
                save();
            } catch (IOException e) {
                LOGGER.log(Level.WARNING,
                        "Could not serialize state. If any of the free plugins are uninstalled, "
                                + "they may be reinstalled on next restart.",
                        e);
            }
        }
    }

    private String getVersionString(PluginWrapper pluginWrapper) {
        String version = pluginWrapper.getVersionNumber().toString();
        int i = version.indexOf(' ');
        version = i == -1 ? version : version.substring(0, i);
        return version;
    }

    public static Localizable getStatus() {
        return status;
    }

    public static boolean isStatusImportant() {
        return statusImportant;
    }

    @Initializer(after = InitMilestone.EXTENSIONS_AUGMENTED, attains = "cloudbees-enterprise-update-center-configured")
    public static void addUpdateCenter() throws Exception {
        LOGGER.log(Level.FINE, "Checking that the CloudBees update center has been configured.");
        UpdateCenter updateCenter = Hudson.getInstance().getUpdateCenter();
        PersistedList<UpdateSite> sites = updateCenter.getSites();
        PluginWrapper plugin = Jenkins.getInstance().getPluginManager().getPlugin("nectar-license");
        if (plugin != null && plugin.isActive()) {
            // delegate to nectar-license once it is installed and active
            List<UpdateSite> forRemoval = new ArrayList<UpdateSite>();
            for (UpdateSite site : sites) {
                if (site instanceof CloudBeesUpdateSite) {
                    forRemoval.add(site);
                }
            }

            // now make the changes if we have any to make
            if (!forRemoval.isEmpty()) {
                BulkChange bc = new BulkChange(updateCenter);
                try {
                    for (UpdateSite site : forRemoval) {
                        sites.remove(site);
                    }
                } finally {
                    bc.commit();
                }
            }
        } else {
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
    }

    @Initializer(requires = "cloudbees-enterprise-update-center-configured")
    public static void installCorePlugins() {
        LOGGER.log(Level.INFO, "Checking that the CloudBees plugins have been installed.");
        PluginImpl instance = Hudson.getInstance().getPlugin(PluginImpl.class);
        if (instance != null && instance.isInstalled()) {
            for (Dependency pluginArtifactId : CLOUDBEES_FREE_PLUGINS) {
                if (pluginArtifactId.mandatory) {
                    LOGGER.log(Level.FINE, "Checking {0}.", pluginArtifactId.name);
                    PluginWrapper plugin = Hudson.getInstance().getPluginManager().getPlugin(pluginArtifactId.name);
                    if (plugin != null && !plugin.isEnabled()) {
                        LOGGER.log(Level.FINE, "Enabling {0}", pluginArtifactId.name);
                        try {
                            plugin.enable();
                        } catch (IOException e) {
                            LOGGER.log(Level.WARNING, "Could not enable " + pluginArtifactId.name, e);
                        }
                    }
                }
            }
            LOGGER.info("Core plugins installation previously completed, will not check or reinstall");
            return;
        }
        for (Dependency pluginArtifactId : CLOUDBEES_FREE_PLUGINS) {
            LOGGER.log(Level.FINE, "Checking {0}.", pluginArtifactId.name);
            PluginWrapper plugin = Hudson.getInstance().getPluginManager().getPlugin(pluginArtifactId.name);
            if (plugin == null && !pluginArtifactId.optional) {
                // not installed and mandatory
                scheduleInstall(pluginArtifactId);
            } else if (plugin != null && (pluginArtifactId.version != null || plugin.getVersion() == null)) {
                // already installed
                if (plugin.getVersionNumber().compareTo(pluginArtifactId.version) < 0) {
                    // but older version
                    scheduleInstall(pluginArtifactId);
                }
            }
            if (pluginArtifactId.mandatory) {
                if (plugin != null && !plugin.isEnabled()) {
                    LOGGER.log(Level.FINE, "Enabling {0}", pluginArtifactId.name);
                    try {
                        plugin.enable();
                    } catch (IOException e) {
                        LOGGER.log(Level.WARNING, "Could not enable " + pluginArtifactId.name, e);
                    }
                }
            }
        }
        boolean finished;
        synchronized (pendingPluginInstalls) {
            finished = pendingPluginInstalls.isEmpty();
            if (!finished && (worker == null || !worker.isAlive())) {
                status = Messages._Notice_downloadUCMetadata();
                LOGGER.info("Starting background thread for core plugin installation");
                worker = new DelayedInstaller();
                worker.setDaemon(true);
                worker.start();
            } else {
                LOGGER.log(Level.INFO, "Nothing to do");
            }
        }
        if (finished && instance != null) {
            instance.setInstalled(true);
        }
    }

    private static void scheduleInstall(Dependency pluginArtifactId) {
        synchronized (pendingPluginInstalls) {
            LOGGER.log(Level.FINE, "Scheduling installation of {0}", pluginArtifactId.name);
            pendingPluginInstalls.add(pluginArtifactId);
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
                                Hudson.getInstance().getUpdateCenter().getSite(CLOUDBEES_UPDATE_CENTER_ID);
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
                    statusImportant = true;
                    try {
                        status = Messages._Notice_scheduledRestart();
                        try {
                            Thread.sleep(5000);
                        } catch (InterruptedException e) {
                            // ignore
                        }
                        Hudson.getInstance().safeRestart();
                        // if the user manually cancelled the quiet down, reflect that in the status message
                        Trigger.timer.scheduleAtFixedRate(new SafeTimerTask() {
                            @Override
                            protected void doRun() throws Exception {
                                if (!Jenkins.getInstance().isQuietingDown()) {
                                    status = null;
                                }
                            }
                        }, 1000, 1000);
                    } catch (RestartNotSupportedException exception) {
                        // ignore if restart is not allowed
                        status = Messages._Notice_restartRequired();
                    }
                }
            } finally {
                LOGGER.info("Background thread for core plugin installation finished.");
                boolean finished;
                synchronized (pendingPluginInstalls) {
                    if (worker == this) {
                        worker = null;
                    }
                    finished = pendingPluginInstalls.isEmpty();
                }
                PluginImpl instance = Hudson.getInstance().getPlugin(PluginImpl.class);
                if (finished && instance != null) {
                    instance.setInstalled(true);
                }
            }
        }

        private boolean progressPluginInstalls(UpdateSite cloudbeesSite) {
            synchronized (pendingPluginInstalls) {
                while (!pendingPluginInstalls.isEmpty()) {
                    Dependency pluginArtifactId = pendingPluginInstalls.get(0);
                    UpdateSite.Plugin p = Hudson.getInstance()
                            .getUpdateCenter()
                            .getSite(CLOUDBEES_UPDATE_CENTER_ID)
                            .getPlugin(pluginArtifactId.name);
                    if (p == null) {
                        if (System.currentTimeMillis() > nextWarning) {
                            LOGGER.log(Level.WARNING,
                                    "Cannot find core plugin {0}, the CloudBees free plugins cannot be "
                                            + "installed without this core plugin. Will try again later.",
                                    pluginArtifactId.name);
                            nextWarning = System.currentTimeMillis() + TimeUnit2.HOURS.toMillis(1);
                        }
                        break;
                    } else if (p.getInstalled() != null && p.getInstalled().isEnabled()) {
                        PluginWrapper plugin = Hudson.getInstance().getPluginManager().getPlugin(pluginArtifactId.name);
                        if (plugin != null && plugin.getVersionNumber().compareTo(pluginArtifactId.version) < 0) {
                            LOGGER.info("Upgrading CloudBees plugin: " + pluginArtifactId.name);
                            status = Messages._Notice_upgradingPlugin(p.getDisplayName(), p.version);
                            Authentication authentication = Hudson.getAuthentication();
                            try {
                                SecurityContextHolder.getContext().setAuthentication(ACL.SYSTEM);
                                p.deploy().get();
                                LOGGER.info("Upgraded CloudBees plugin: " + pluginArtifactId.name + " to " + p.version);
                                pendingPluginInstalls.remove(0);
                                nextWarning = 0;
                                status = Messages._Notice_upgradedPlugin(p.getDisplayName(), p.version);
                            } catch (Throwable e) {
                                if (System.currentTimeMillis() > nextWarning) {
                                    LOGGER.log(Level.WARNING,
                                            "Cannot upgrade CloudBees plugin: " + pluginArtifactId.name + " to "
                                                    + p.version, e);
                                    nextWarning = System.currentTimeMillis() + TimeUnit2.MINUTES.toMillis(1);
                                }
                                break;
                            } finally {
                                SecurityContextHolder.getContext().setAuthentication(authentication);
                            }
                        } else {
                            LOGGER.info("Detected previous installation of CloudBees plugin: " + pluginArtifactId.name);
                            pendingPluginInstalls.remove(0);
                            nextWarning = 0;
                        }
                    } else {
                        LOGGER.info("Installing CloudBees plugin: " + pluginArtifactId.name + " version " + p.version);
                        status = Messages._Notice_installingPlugin(p.getDisplayName());
                        Authentication authentication = Hudson.getAuthentication();
                        try {
                            SecurityContextHolder.getContext().setAuthentication(ACL.SYSTEM);
                            p.deploy().get();
                            LOGGER.info(
                                    "Installed CloudBees plugin: " + pluginArtifactId.name + " version " + p.version);
                            pendingPluginInstalls.remove(0);
                            nextWarning = 0;
                            status = Messages._Notice_installedPlugin(p.getDisplayName());
                        } catch (Throwable e) {
                            if (System.currentTimeMillis() > nextWarning) {
                                LOGGER.log(Level.WARNING,
                                        "Cannot install CloudBees plugin: " + pluginArtifactId.name + " version "
                                                + p.version, e);
                                nextWarning = System.currentTimeMillis() + TimeUnit2.MINUTES.toMillis(1);
                            }
                            break;
                        } finally {
                            SecurityContextHolder.getContext().setAuthentication(authentication);
                        }
                    }
                }
                return !pendingPluginInstalls.isEmpty();
            }
        }
    }

    private static Dependency require(String name) {
        return require(name, null);
    }

    private static Dependency require(String name, String version) {
        return new Dependency(name, version, false, false);
    }

    private static Dependency optional(String name) {
        return optional(name, null);
    }

    private static Dependency optional(String name, String version) {
        return new Dependency(name, version, true, false);
    }

    private static class Dependency {
        public final String name;
        public final VersionNumber version;
        public final boolean optional;
        public final boolean mandatory;

        private Dependency(String name, String version, boolean optional, boolean mandatory) {
            this.name = name;
            this.version = version == null ? null : new VersionNumber(version);
            this.optional = optional;
            this.mandatory = mandatory;
        }

        public Dependency mandatory() {
            return new Dependency(name, version == null ? null : version.toString(), optional, mandatory);
        }
    }
}