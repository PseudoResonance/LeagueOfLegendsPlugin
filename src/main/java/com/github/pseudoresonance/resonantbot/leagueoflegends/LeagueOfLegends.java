package com.github.pseudoresonance.resonantbot.leagueoflegends;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLConnection;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.LinkedList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import javax.json.Json;
import javax.json.JsonArray;
import javax.json.JsonException;
import javax.json.JsonReader;
import javax.net.ssl.HttpsURLConnection;

import org.rauschig.jarchivelib.Archiver;
import org.rauschig.jarchivelib.ArchiverFactory;

import com.github.pseudoresonance.resonantbot.ResonantBot;
import com.github.pseudoresonance.resonantbot.api.Plugin;

public class LeagueOfLegends extends Plugin {

	private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(1);
	private File assetDir = null;
	private File downloadDir = null;

	private static File patchDir = null;
	private static String patch = null;

	public void onEnable() {
		assetDir = new File(this.getFolder(), "assets");
		assetDir.mkdirs();
		downloadDir = new File(this.getFolder(), "assetDownloads");
		downloadDir.mkdirs();
		updateAssets();
		LeagueOfLegendsCommand.setup(this);
		scheduler.scheduleAtFixedRate(() -> {
			LeagueOfLegendsCommand.purge();
		}, 13, 12, TimeUnit.HOURS);
		scheduler.scheduleAtFixedRate(() -> {
			updateAssets();
		}, 6, 6, TimeUnit.HOURS);
	}

	public void onDisable() {
		scheduler.shutdownNow();
	}

	public static File getPatchDataDirectory() {
		return patchDir;
	}

	public static String getPatchName() {
		return patch;
	}

	private LinkedList<String> getLatestPatch() {
		LinkedList<String> ret = new LinkedList<String>();
		try {
			URL url = new URL("https://ddragon.leagueoflegends.com/api/versions.json");
			HttpsURLConnection connection = (HttpsURLConnection) url.openConnection();
			try (InputStream in = connection.getInputStream()) {
				try (JsonReader jr = Json.createReader(in)) {
					JsonArray ja = jr.readArray();
					if (ja.size() >= 3)
						for (int i = 0; i < 3; i++)
							ret.add(ja.getString(i));
					return ret;
				} catch (JsonException e) {
					ResonantBot.getBot().getLogger().error("Unable to fetch latest League of Legends patch version!");
					e.printStackTrace();
				}
			}
		} catch (IOException e) {
			ResonantBot.getBot().getLogger().error("Unable to fetch latest League of Legends patch version!");
			e.printStackTrace();
		}
		return null;
	}

	private void updateAssets() {
		LinkedList<String> patch = getLatestPatch();
		if (patch == null || patch.isEmpty()) {
			ResonantBot.getBot().getLogger().error("Unable to fetch latest League of Legends assets!");
			return;
		} else {
			File[] assetList = assetDir.listFiles();
			boolean foundLatest = false;
			for (File f : assetList) {
				if (f.isDirectory()) {
					String name = f.getName();
					if (!patch.contains(name)) {
						try {
							Files.walk(f.toPath()).sorted(Comparator.reverseOrder()).map(Path::toFile).forEach(File::delete);
						} catch (IOException e) {
							ResonantBot.getBot().getLogger().error("Unable to delete outdated League of Legends assets: " + f.getAbsolutePath());
							e.printStackTrace();
						}
					} else if (patch.get(0).equals(name))
						foundLatest = true;
				}
			}
			File[] downloadList = downloadDir.listFiles();
			for (File f : downloadList) {
				if (f.isFile()) {
					String name = f.getName();
					if (!patch.contains(name))
						f.delete();
				}
			}
			if (!foundLatest) {
				String urlStr = "https://ddragon.leagueoflegends.com/cdn/dragontail-" + patch.get(0) + ".tgz";
				try {
					boolean downloadSuccessful = false;
					File download = new File(downloadDir, patch.get(0) + ".tgz");
					URL url = new URL(urlStr);
					for (int i = 0; i < 5; i++) {
						try {
							if (downloadFile(url, download)) {
								downloadSuccessful = true;
								break;
							}
						} catch (Exception e) {
						}
					}
					if (downloadSuccessful) {
						File dataDir = new File(assetDir, patch.get(0));
						dataDir.mkdirs();
						if (extractArchive(download, dataDir)) {
							patchDir = dataDir;
							LeagueOfLegends.patch = patch.get(0);
							ResonantBot.getBot().getLogger().info("Downloaded League of Legends assets for patch: " + patch.get(0));
						} else
							return;
					} else
						return;
				} catch (MalformedURLException e) {
					ResonantBot.getBot().getLogger().error("Unable to fetch latest League of Legends assets!");
					ResonantBot.getBot().getLogger().error(urlStr);
					e.printStackTrace();
					return;
				}
			} else {
				patchDir = new File(assetDir, patch.get(0));
				LeagueOfLegends.patch = patch.get(0);
				ResonantBot.getBot().getLogger().info("Using League of Legends assets for patch: " + patch.get(0));
			}
		}
	}

	private boolean downloadFile(URL url, File destination) throws IOException {
		long existingFileSize = 0L;
		URLConnection downloadFileConnection = url.openConnection();
		long fileLength = 0;
		if (destination.exists() && downloadFileConnection instanceof HttpURLConnection) {
			HttpURLConnection httpFileConnection = (HttpURLConnection) downloadFileConnection;

			HttpURLConnection tmpFileConn = (HttpURLConnection) url.openConnection();
			tmpFileConn.setRequestMethod("HEAD");
			fileLength = tmpFileConn.getContentLengthLong();
			existingFileSize = destination.length();

			if (existingFileSize < fileLength) {
				httpFileConnection.setRequestProperty("Range", "bytes=" + existingFileSize + "-" + fileLength);
			} else
				return true;
		}
		try (InputStream is = downloadFileConnection.getInputStream(); FileOutputStream os = new FileOutputStream(destination, true)) {
			byte[] buffer = new byte[1024];
			int bytesCount;
			while ((bytesCount = is.read(buffer)) > 0) {
				os.write(buffer, 0, bytesCount);
			}
		}
		if (destination.length() >= fileLength)
			return true;
		return false;
	}

	private boolean extractArchive(File source, File destination) {
		try {
			Archiver archiver = ArchiverFactory.createArchiver("tar", "gz");
			archiver.extract(source, destination);
			return true;
		} catch (Exception e) {
			ResonantBot.getBot().getLogger().error("Unable to extract League of Legends assets!");
			ResonantBot.getBot().getLogger().error("From: " + source.getAbsolutePath() + " To: " + destination.getAbsolutePath());
			e.printStackTrace();
		}
		return false;
	}

}
