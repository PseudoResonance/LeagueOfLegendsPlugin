package com.github.pseudoresonance.resonantbot.leagueoflegends;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import com.github.pseudoresonance.resonantbot.api.Plugin;

public class LeagueOfLegends extends Plugin {

	private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(1);

	public static LeagueOfLegends plugin;

	public void onEnable() {
		LeagueOfLegends.plugin = this;
		LeagueOfLegendsCommand.setup(this);
		scheduler.scheduleAtFixedRate(() -> {
			LeagueOfLegendsCommand.purge();
		}, 13, 12, TimeUnit.HOURS);
	}

	public void onDisable() {
		scheduler.shutdownNow();
	}

}
