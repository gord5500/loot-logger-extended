package com.ferocitylootlogger;

import com.google.common.base.Strings;
import com.google.inject.Provides;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.sql.SQLOutput;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import javax.imageio.ImageIO;
import javax.inject.Inject;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.ItemComposition;
import net.runelite.api.NPC;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.events.ConfigChanged;
import net.runelite.client.events.NpcLootReceived;
import net.runelite.client.events.PlayerLootReceived;
import net.runelite.client.game.ItemManager;
import net.runelite.client.game.ItemStack;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.plugins.loottracker.LootReceived;
import net.runelite.client.ui.DrawManager;
import net.runelite.client.util.ImageCapture;
import net.runelite.client.util.QuantityFormatter;
import net.runelite.client.util.Text;
import net.runelite.client.util.WildcardMatcher;
import static net.runelite.http.api.RuneLiteAPI.GSON;
import net.runelite.http.api.loottracker.LootRecordType;
import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.HttpUrl;
import okhttp3.MediaType;
import okhttp3.MultipartBody;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

@Slf4j
@PluginDescriptor(
		name = "Ferocity Discord Loot Logger"
)
public class FerocityLoggerPlugin extends Plugin
{
	@Inject
	private FerocityLoggerConfig config;

	@Inject
	private ItemManager itemManager;

	@Inject
	private OkHttpClient okHttpClient;

	@Inject
	private ImageCapture imageCapture;

	@Inject
	private DrawManager drawManager;

	private List<String> lootNpcs;
	private List<String> lootOtherItems;

	private static String itemImageUrl(int itemId)
	{
		return "https://static.runelite.net/cache/item/icon/" + itemId + ".png";
	}

	@Inject
	private Client client;

	@Override
	protected void startUp()
	{

		lootNpcs = Collections.emptyList();
		String otherItems = config.otherItems();
		lootOtherItems = otherItems != null ? Text.fromCSV(otherItems) : Collections.emptyList();
	}

	@Override
	protected void shutDown()
	{
	}

	@Provides
	FerocityLoggerConfig provideConfig(ConfigManager configManager)
	{
		return configManager.getConfig(FerocityLoggerConfig.class);
	}

	@Subscribe
	public void onConfigChanged(ConfigChanged configChanged)
	{
		if (configChanged.getGroup().equalsIgnoreCase(FerocityLoggerConfig.GROUP))
		{
			String npcNames = config.lootNpcs();
			lootNpcs = npcNames != null ? Text.fromCSV(npcNames) : Collections.emptyList();

			String otherItems = config.otherItems();
			lootOtherItems = otherItems != null ? Text.fromCSV(otherItems) : Collections.emptyList();
		}
	}

	@Subscribe
	public void onNpcLootReceived(NpcLootReceived npcLootReceived)
	{
		NPC npc = npcLootReceived.getNpc();
		Collection<ItemStack> items = npcLootReceived.getItems();

		if (!lootNpcs.isEmpty())
		{
			for (String npcName : lootNpcs)
			{
				if (WildcardMatcher.matches(npcName, npc.getName()))
				{
					processLoot(npc.getName(), items);
					return;
				}
			}
		}
		else
		{
			processLoot(npc.getName(), items);
		}
	}

	@Subscribe
	public void onPlayerLootReceived(PlayerLootReceived playerLootReceived)
	{
		Collection<ItemStack> items = playerLootReceived.getItems();
		processLoot(playerLootReceived.getPlayer().getName(), items);
	}

	@Subscribe
	public void onLootReceived(LootReceived lootReceived)
	{
		if (lootReceived.getType() != LootRecordType.EVENT && lootReceived.getType() != LootRecordType.PICKPOCKET)
		{
			return;
		}

		processLoot(lootReceived.getName(), lootReceived.getItems());
	}

	private String getPlayerName()
	{
		return client.getLocalPlayer().getName();
	}

	private void processLoot(String name, Collection<ItemStack> items)
	{
		WebhookBody webhookBody = new WebhookBody();

		boolean sendMessage = false;
		long totalValue = 0;
		StringBuilder stringBuilder = new StringBuilder();
		if (config.includeUsername())
		{
			stringBuilder.append("\n**").append(getPlayerName()).append("**").append(":\n\n");
		}
		stringBuilder.append("***").append(name).append("***").append(":\n");
		final int targetValue = config.lootValue();
		for (ItemStack item : stack(items))
		{
			int itemId = item.getId();
			int qty = item.getQuantity();

			int price = itemManager.getItemPrice(itemId);
			long total = (long) price * qty;

			totalValue += total;

			if (config.includeLowValueItems() || total >= targetValue || isOtherItem(client.getItemDefinition(item.getId()).getName().toLowerCase()))
			{
				sendMessage = true;
				ItemComposition itemComposition = itemManager.getItemComposition(itemId);
				stringBuilder.append("*").append(qty).append(" x ").append(itemComposition.getName()).append("*");
				if (config.stackValue())
				{
					stringBuilder.append(" (").append(QuantityFormatter.quantityToStackSize(total)).append(")");
				}
				stringBuilder.append("\n");
				webhookBody.getEmbeds().add(new WebhookBody.Embed(new WebhookBody.UrlEmbed(itemImageUrl(itemId))));
			}
		}

		if (sendMessage)
		{
			webhookBody.setContent(stringBuilder.toString());
			sendWebhook(webhookBody);
		}
	}

	private boolean isOtherItem(String name) {

		return lootOtherItems.stream().anyMatch(item -> WildcardMatcher.matches(item, name));
	}

	private void sendWebhook(WebhookBody webhookBody)
	{
		String configUrl = config.webhook();
		if (Strings.isNullOrEmpty(configUrl))
		{
			return;
		}

		HttpUrl url = HttpUrl.parse(configUrl);
		MultipartBody.Builder requestBodyBuilder = new MultipartBody.Builder()
				.setType(MultipartBody.FORM)
				.addFormDataPart("payload_json", GSON.toJson(webhookBody));

		if (config.sendScreenshot())
		{
			sendWebhookWithScreenshot(url, requestBodyBuilder);
		}
		else
		{
			buildRequestAndSend(url, requestBodyBuilder);
		}
	}

	private void sendWebhookWithScreenshot(HttpUrl url, MultipartBody.Builder requestBodyBuilder)
	{
		drawManager.requestNextFrameListener(image ->
		{
			BufferedImage bufferedImage = (BufferedImage) image;
			byte[] imageBytes;
			try
			{
				imageBytes = convertImageToByteArray(bufferedImage);
			}
			catch (IOException e)
			{
				log.warn("Error converting image to byte array", e);
				return;
			}

			requestBodyBuilder.addFormDataPart("file", "image.png",
					RequestBody.create(MediaType.parse("image/png"), imageBytes));
			buildRequestAndSend(url, requestBodyBuilder);
		});
	}

	private void buildRequestAndSend(HttpUrl url, MultipartBody.Builder requestBodyBuilder)
	{
		RequestBody requestBody = requestBodyBuilder.build();
		Request request = new Request.Builder()
				.url(url)
				.post(requestBody)
				.build();
		sendRequest(request);
	}

	private void sendRequest(Request request)
	{
		okHttpClient.newCall(request).enqueue(new Callback()
		{
			@Override
			public void onFailure(Call call, IOException e)
			{
				log.debug("Error submitting webhook", e);
			}

			@Override
			public void onResponse(Call call, Response response) throws IOException
			{
				response.close();
			}
		});
	}

	private static byte[] convertImageToByteArray(BufferedImage bufferedImage) throws IOException
	{
		ByteArrayOutputStream byteArrayOutputStream = new ByteArrayOutputStream();
		ImageIO.write(bufferedImage, "png", byteArrayOutputStream);
		return byteArrayOutputStream.toByteArray();
	}

	private static Collection<ItemStack> stack(Collection<ItemStack> items)
	{
		final List<ItemStack> list = new ArrayList<>();

		for (final ItemStack item : items)
		{
			int quantity = 0;
			for (final ItemStack i : list)
			{
				if (i.getId() == item.getId())
				{
					quantity = i.getQuantity();
					list.remove(i);
					break;
				}
			}
			if (quantity > 0)
			{
				list.add(new ItemStack(item.getId(), item.getQuantity() + quantity, item.getLocation()));
			}
			else
			{
				list.add(item);
			}
		}

		return list;
	}
}