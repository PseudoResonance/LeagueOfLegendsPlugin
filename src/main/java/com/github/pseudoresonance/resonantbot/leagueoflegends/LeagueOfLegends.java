package com.github.pseudoresonance.resonantbot.leagueoflegends;

import com.github.pseudoresonance.resonantbot.api.Plugin;

public class LeagueOfLegends extends Plugin {

	public void onEnable() {
		LeagueOfLegendsCommand.setup(this);
	}
	
	public void onDisable() {
	}
	
}
