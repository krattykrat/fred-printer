package com.example.addon;

import com.example.addon.modules.StatuePrinter;

import com.mojang.logging.LogUtils;
import meteordevelopment.meteorclient.addons.GithubRepo;
import meteordevelopment.meteorclient.addons.MeteorAddon;
import meteordevelopment.meteorclient.systems.modules.Modules;
import org.slf4j.Logger;

public class FredPrinter extends MeteorAddon {
    public static final Logger LOG = LogUtils.getLogger();

    @Override
    public void onInitialize() {
        LOG.info("Initializing Fred Printer");

        // The module lives in Meteor's built-in Misc category, so no custom category is registered.
        Modules.get().add(new StatuePrinter());
    }

    @Override
    public String getPackage() {
        return "com.example.addon";
    }

    @Override
    public GithubRepo getRepo() {
        return null;   // no public repository
    }
}
