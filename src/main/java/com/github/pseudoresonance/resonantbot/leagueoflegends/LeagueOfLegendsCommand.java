package com.github.pseudoresonance.resonantbot.leagueoflegends;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.net.URLEncoder;
import java.sql.Timestamp;
import java.text.DecimalFormat;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.Iterator;
import java.util.Map.Entry;
import java.util.NoSuchElementException;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;

import javax.imageio.ImageIO;

import org.apache.commons.text.WordUtils;

import com.github.pseudoresonance.resonantbot.ResonantBot;
import com.github.pseudoresonance.resonantbot.api.CommandHandler;
import com.github.pseudoresonance.resonantbot.api.Plugin;
import com.github.pseudoresonance.resonantbot.data.Data;
import com.github.pseudoresonance.resonantbot.language.Language;
import com.github.pseudoresonance.resonantbot.language.LanguageManager;
import com.github.pseudoresonance.resonantbot.permissions.PermissionGroup;

import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.entities.Message;
import net.dv8tion.jda.api.events.message.MessageReceivedEvent;
import net.rithms.riot.api.ApiConfig;
import net.rithms.riot.api.RiotApi;
import net.rithms.riot.api.RiotApiException;
import net.rithms.riot.api.endpoints.league.dto.LeagueEntry;
import net.rithms.riot.api.endpoints.summoner.dto.Summoner;
import net.rithms.riot.constant.Platform;

public class LeagueOfLegendsCommand {

	private static final long SUMMONER_EXPIRY = 900000;

	private static CommandHandler cmd = null;
	private static ApiConfig config = null;
	private static RiotApi api = null;

	private static String apiKey = "";

	private final static DecimalFormat df = new DecimalFormat("#.#");

	private static ConcurrentHashMap<String, ExpiryHolder<Summoner>> summonerNameCache = new ConcurrentHashMap<String, ExpiryHolder<Summoner>>();
	private static ConcurrentHashMap<String, ExpiryHolder<Summoner>> summonerIdCache = new ConcurrentHashMap<String, ExpiryHolder<Summoner>>();
	private static ConcurrentHashMap<String, ExpiryHolder<Summoner>> summonerAccountIdCache = new ConcurrentHashMap<String, ExpiryHolder<Summoner>>();
	private static ConcurrentHashMap<String, ExpiryHolder<Summoner>> summonerPuuidCache = new ConcurrentHashMap<String, ExpiryHolder<Summoner>>();

	private static ConcurrentHashMap<String, ExpiryHolder<Set<LeagueEntry>>> summonerLeagueEntriesCache = new ConcurrentHashMap<String, ExpiryHolder<Set<LeagueEntry>>>();

	private static ConcurrentHashMap<String, String> summonerPictureCache = new ConcurrentHashMap<String, String>();

	public static void setup(Plugin plugin) {
		if (api == null) {
			Object keyO = Data.getBotSetting("leagueoflegends_key");
			if (keyO instanceof String) {
				String key = (String) keyO;
				apiKey = key;
				config = new ApiConfig().setKey(key);
				api = new RiotApi(config);
			}
		}
		cmd = new CommandHandler("leagueoflegends", "leagueoflegends.lolCommandDescription");
		cmd.registerSubcommand("apikey", (e, command, args) -> {
			if (args.length > 0) {
				e.getMessage().delete().queue();
				Data.setBotSetting("leagueoflegends_key", args[0]);
				config.setKey(args[0]);
				apiKey = args[0];
				e.getChannel().sendMessage(LanguageManager.getLanguage(e).getMessage("main.savedAPIKey")).queue();
			} else {
				e.getChannel().sendMessage(LanguageManager.getLanguage(e).getMessage("main.validAPIKey")).queue();
			}
			return true;
		}, PermissionGroup.BOT_OWNER);
		cmd.registerSubcommand("summoner", (e, command, args) -> {
			if (apiKey.isBlank()) {
				e.getChannel().sendMessage(LanguageManager.getLanguage(e).getMessage("main.noAPIKey")).queue();
				return true;
			}
			boolean showPlatforms = false;
			if (args.length == 0)
				showPlatforms = true;
			else if (args.length >= 1) {
				Platform platform = null;
				String name = "";
				try {
					platform = getPlatformByName(args[0]);
				} catch (NoSuchElementException e1) {
					for (int i = 0; i < args.length; i++)
						name += args[i] + " ";
					name = name.substring(0, name.length() - 1);
					String[] split = name.split("#");
					if (split.length == 2) {
						try {
							platform = getPlatformByName(split[1]);
							name = split[0];
						} catch (NoSuchElementException e2) {
							showPlatforms = true;
						}
					} else
						showPlatforms = true;
				}
				if (platform != null && name.isEmpty()) {
					if (args.length == 1) {
						e.getChannel().sendMessage(LanguageManager.getLanguage(e).getMessage("main.validSubcommands", "`" + LanguageManager.getLanguage(e).getMessage("leagueoflegends.summoner").toLowerCase() + "`")).queue();
						return true;
					}
				} else if (platform == null)
					showPlatforms = true;
				if (!showPlatforms) {
					CompletableFuture<Message> placeholder = e.getChannel().sendMessage(LanguageManager.getLanguage(e).getMessage("main.fetchingData")).submit();
					try {
						if (name.isEmpty()) {
							for (int i = 1; i < args.length; i++)
								name += args[i] + " ";
							name = name.substring(0, name.length() - 1);
						}
						ExpiryHolder<Summoner> cachedSummoner = checkSummonerCache(name, platform);
						Summoner summoner = null;
						if (cachedSummoner == null || cachedSummoner.isExpired()) {
							if (name.length() <= 16) {
								summoner = api.getSummonerByName(platform, name);
							} else if (name.length() == 78) {
								summoner = api.getSummonerByPuuid(platform, name);
							} else {
								summoner = api.getSummonerByAccount(platform, name);
							}
							cachedSummoner = updateSummonerCache(summoner, platform);
						} else
							summoner = cachedSummoner.getObject();
						ExpiryHolder<Set<LeagueEntry>> cachedLeagueEntries = checkSummonerLeagueEntriesCache(summoner.getPuuid());
						Set<LeagueEntry> leagueEntries = null;
						if (cachedLeagueEntries == null || cachedLeagueEntries.isExpired()) {
							leagueEntries = api.getLeagueEntriesBySummonerId(platform, summoner.getId());
							cachedLeagueEntries = updateSummonerLeagueEntriesCache(summoner.getPuuid(), cachedSummoner.getCreation(), leagueEntries);
						}
						try {
							sendSummonerInformation(e, cachedSummoner, platform, cachedLeagueEntries, placeholder);
						} catch (Exception e1) {
							e1.printStackTrace();
							String text = LanguageManager.getLanguage(e).getMessage("main.errorOccurred");
							try {
								Message msg = placeholder.get();
								msg.editMessage(text).queue();
							} catch (InterruptedException | ExecutionException e2) {
								e.getChannel().sendMessage(text).queue();
							}
						}
						return true;
					} catch (IllegalArgumentException e1) {
						String text = LanguageManager.getLanguage(e).getMessage("leagueoflegends.invalidSummoner");
						try {
							Message msg = placeholder.get();
							msg.editMessage(text).queue();
						} catch (InterruptedException | ExecutionException e2) {
							e.getChannel().sendMessage(text).queue();
						}
					} catch (RiotApiException e1) {
						String text = "";
						if (e1.getErrorCode() == 403)
							text = LanguageManager.getLanguage(e).getMessage("main.noAPIKey");
						else if (e1.getErrorCode() == 404)
							text = LanguageManager.getLanguage(e).getMessage("leagueoflegends.invalidSummoner");
						else {
							e1.printStackTrace();
							text = LanguageManager.getLanguage(e).getMessage("main.errorOccurred");
						}
						try {
							Message msg = placeholder.get();
							msg.editMessage(text).queue();
						} catch (InterruptedException | ExecutionException e2) {
							e.getChannel().sendMessage(text).queue();
						}
					}
					return true;
				}
			}
			if (showPlatforms) {
				String options = "";
				for (Platform platform : Platform.values())
					options += "`" + platform.getName().toLowerCase() + "`, ";
				options = options.substring(0, options.length() - 1);
				e.getChannel().sendMessage(LanguageManager.getLanguage(e).getMessage("main.validSubcommands", options)).queue();
				return true;
			}
			return false;
		});
		cmd.register(plugin);
	}

	private static ExpiryHolder<Summoner> checkSummonerCache(String input, Platform platform) {
		input = input.toLowerCase();
		if (input.length() <= 16) {
			return summonerNameCache.get(input + "#" + platform.getId());
		} else if (input.length() == 78) {
			return summonerPuuidCache.get(input);
		} else {
			ExpiryHolder<Summoner> test = summonerIdCache.get(input);
			if (test == null)
				return summonerAccountIdCache.get(input);
			return test;
		}
	}

	private static ExpiryHolder<Set<LeagueEntry>> checkSummonerLeagueEntriesCache(String puuid) {
		puuid = puuid.toLowerCase();
		return summonerLeagueEntriesCache.get(puuid);
	}

	private static ExpiryHolder<Summoner> updateSummonerCache(Summoner summoner, Platform platform) {
		String newName = summoner.getName().toLowerCase();
		ExpiryHolder<Summoner> check = summonerPuuidCache.get(summoner.getPuuid());
		if (check != null) {
			String oldName = check.getObject().getName();
			if (!oldName.equals(newName))
				summonerNameCache.remove(oldName);
		}
		ExpiryHolder<Summoner> expiry = new ExpiryHolder<Summoner>(summoner, SUMMONER_EXPIRY);
		summonerNameCache.put(newName + "#" + platform.getId(), expiry);
		summonerIdCache.put(summoner.getId().toLowerCase(), expiry);
		summonerAccountIdCache.put(summoner.getAccountId().toLowerCase(), expiry);
		summonerPuuidCache.put(summoner.getPuuid().toLowerCase(), expiry);
		return expiry;
	}

	private static ExpiryHolder<Set<LeagueEntry>> updateSummonerLeagueEntriesCache(String puuid, long creation, Set<LeagueEntry> entries) {
		ExpiryHolder<Set<LeagueEntry>> expiry = new ExpiryHolder<Set<LeagueEntry>>(entries, SUMMONER_EXPIRY);
		expiry.setExpiry(creation, SUMMONER_EXPIRY);
		summonerLeagueEntriesCache.put(puuid.toLowerCase(), expiry);
		return expiry;
	}

	private static Platform getPlatformByName(String name) {
		for (Platform platform : Platform.values()) {
			if (platform.getName().equalsIgnoreCase(name) || platform.getId().equalsIgnoreCase(name)) {
				return platform;
			}
		}
		throw new NoSuchElementException("Unknown platform name: " + name);
	}

	private static void sendSummonerInformation(MessageReceivedEvent e, ExpiryHolder<Summoner> summonerHolder, Platform platform, ExpiryHolder<Set<LeagueEntry>> leagueEntries, CompletableFuture<Message> placeholder) {
		try {
			Summoner summoner = summonerHolder.getObject();
			Set<LeagueEntry> leagues = leagueEntries.getObject();
			Language lang = LanguageManager.getLanguage(e);
			EmbedBuilder embed = new EmbedBuilder();
			embed.setFooter(lang.formatTimeAgo(new Timestamp(summonerHolder.getCreation())));
			embed.setColor(new Color(224, 201, 121));
			embed.setTitle(lang.getMessage("leagueoflegends.summonerinfo", LanguageManager.escape(summoner.getName() + "#" + platform.getId()), summoner.getSummonerLevel()), "https://" + platform.getName() + ".op.gg/summoner/userName=" + URLEncoder.encode(summoner.getName(), "UTF-8"));
			embed.setThumbnail("attachment://thumb.png");
			LeagueTier highestTier = null;
			String tierRank = "";
			for (LeagueEntry entry : leagues) {
				LeagueTier tier = LeagueTier.valueOf(entry.getTier().toUpperCase());
				if (tier != null) {
					if (highestTier == null || tier.getId() < highestTier.getId()) {
						highestTier = tier;
						tierRank = entry.getRank();
					}
				}
				String entryStats = lang.getMessage("leagueoflegends.tier", WordUtils.capitalizeFully(tier.toString()) + (tier != LeagueTier.CHALLENGER && tier != LeagueTier.GRANDMASTER && tier != LeagueTier.MASTER ? " " + entry.getRank().toUpperCase() : ""));
				entryStats += "\n" + lang.getMessage("leagueoflegends.winlossratio", entry.getWins(), entry.getLosses(), df.format((entry.getWins() / (double) (entry.getWins() + entry.getLosses()) * 100)));
				entryStats += "\n" + entry.getLeaguePoints() + " LP" + (entry.isVeteran() ? " " + lang.getMessage("leagueoflegends.veteran") : "");
				if (entry.isFreshBlood() || entry.isHotStreak() || entry.isInactive())
					entryStats += "\n" + (entry.isInactive() ? " " + lang.getMessage("leagueoflegends.inactive") : "") + (entry.isFreshBlood() ? " " + lang.getMessage("leagueoflegends.freshBlood") : "") + (entry.isHotStreak() ? " " + lang.getMessage("leagueoflegends.hotStreak") : "");
				embed.addField(WordUtils.capitalizeFully(entry.getQueueType().replace('_', ' ')), entryStats, true);
			}
			LocalDateTime revisionDate = LocalDateTime.ofInstant(Instant.ofEpochMilli(summoner.getRevisionDate()), ZoneId.systemDefault());
			long daysSinceRevision = ChronoUnit.DAYS.between(revisionDate, LocalDateTime.now());
			String description = "";
			if (daysSinceRevision >= 1)
				description += lang.formatDateTime(revisionDate);
			else
				description += lang.formatTimeAgo(revisionDate, false);
			description = lang.getMessage("leagueoflegends.updated", lang.getMessage("date.relativeAgo", description));
			description = "`" + summoner.getAccountId() + "`\n" + description;
			description += "\n" + lang.getMessage("leagueoflegends.tier", highestTier == null ? lang.getMessage("leagueoflegends.unranked") : WordUtils.capitalizeFully(highestTier.toString()) + (highestTier != LeagueTier.CHALLENGER && highestTier != LeagueTier.GRANDMASTER && highestTier != LeagueTier.MASTER ? " " + tierRank.toUpperCase() : ""));
			embed.setDescription(description);
			String url = getProfilePictureCache(summoner.getProfileIconId(), highestTier);
			if (url != null) {
				embed.setThumbnail(url);
				try {
					Message msg = placeholder.get();
					msg.editMessage(embed.build()).override(true).queue();
				} catch (InterruptedException | ExecutionException e1) {
					e.getChannel().sendMessage(embed.build()).queue();
				}
			} else {
				InputStream thumb = getProfilePicture(summoner, highestTier);
				if (thumb == null) {
					embed.setThumbnail("https://opgg-static.akamaized.net/images/profile_icons/profileIcon" + summoner.getProfileIconId() + ".jpg");
					try {
						Message msg = placeholder.get();
						msg.editMessage(embed.build()).override(true).queue();
					} catch (InterruptedException | ExecutionException e1) {
						e.getChannel().sendMessage(embed.build()).queue();
					}
				} else {
					try {
						Message msg = placeholder.get();
						msg.delete().complete();
					} catch (NullPointerException | InterruptedException | ExecutionException e1) {
					}
					Message sent = e.getChannel().sendMessage(embed.build()).addFile(thumb, "thumb.png").complete();
					summonerPictureCache.put((highestTier == null ? "" : highestTier.toString().toLowerCase()) + summoner.getProfileIconId(), sent.getAttachments().get(0).getUrl());
				}
				if (thumb != null)
					thumb.close();
			}
		} catch (IOException e1) {
			e1.printStackTrace();
			ResonantBot.getBot().getLogger().error("Error overlaying border on profile picture!");
		}
	}

	private static String getProfilePictureCache(int pictureId, LeagueTier tier) {
		return summonerPictureCache.get((tier == null ? "" : tier.toString().toLowerCase()) + pictureId);
	}

	private static InputStream getProfilePicture(Summoner summoner, LeagueTier tier) {
		File iconFile = new File(LeagueOfLegends.getPatchDataDirectory(), LeagueOfLegends.getPatchName() + File.separator + "img" + File.separator + "profileicon" + File.separator + summoner.getProfileIconId() + ".png");
		if (tier == null) {
			try {
				return new FileInputStream(iconFile);
			} catch (FileNotFoundException e) {
				e.printStackTrace();
				return null;
			}
		}
		try {
			BufferedImage icon = ImageIO.read(iconFile);
			BufferedImage border = ImageIO.read(new URL("https://opgg-static.akamaized.net/images/borders2/" + tier.toString().toLowerCase() + ".png"));
			BufferedImage combined = new BufferedImage(120, 120, BufferedImage.TYPE_INT_ARGB);
			Graphics2D g = combined.createGraphics();
			g.drawImage(icon, 10, 10, 100, 100, null);
			g.drawImage(border, 0, 0, null);
			final ByteArrayOutputStream output = new ByteArrayOutputStream() {
				@Override
				public synchronized byte[] toByteArray() {
					return this.buf;
				}
			};
			ImageIO.write(combined, "png", output);
			return new ByteArrayInputStream(output.toByteArray(), 0, output.size());
		} catch (IOException e) {
			e.printStackTrace();
			return null;
		}
	}

	public static void purge() {
		Iterator<Entry<String, ExpiryHolder<Summoner>>> iter = summonerNameCache.entrySet().iterator();
		while (iter.hasNext()) {
			Entry<String, ExpiryHolder<Summoner>> n = iter.next();
			if (n.getValue().isExpired()) {
				Summoner s = n.getValue().getObject();
				summonerIdCache.remove(s.getId());
				summonerAccountIdCache.remove(s.getAccountId());
				summonerPuuidCache.remove(s.getPuuid());
				summonerLeagueEntriesCache.remove(s.getPuuid());
				iter.remove();
			}
		}
	}

	private static enum LeagueTier {
		CHALLENGER(1), GRANDMASTER(2), MASTER(3), DIAMOND(4), PLATINUM(5), GOLD(6), SILVER(7), BRONZE(8), IRON(9);

		int id = 0;

		LeagueTier(int id) {
			this.id = id;
		}

		public int getId() {
			return id;
		}
	}

	private static class ExpiryHolder<T> {

		private T object;
		private long creation;
		private long expiry;

		public ExpiryHolder(T object, long expiry) {
			this.object = object;
			this.creation = System.currentTimeMillis();
			this.expiry = creation + expiry;
		}

		public void setExpiry(long creation, long expiry) {
			this.creation = creation;
			this.expiry = this.creation + expiry;
		}

		public T getObject() {
			return this.object;
		}

		public long getCreation() {
			return this.creation;
		}

		public boolean isExpired() {
			return System.currentTimeMillis() >= this.expiry;
		}

	}

}
