package com.hrznstudio.emojiful;

import com.esotericsoftware.yamlbeans.YamlException;
import com.esotericsoftware.yamlbeans.YamlReader;
import com.google.gson.JsonElement;
import com.hrznstudio.emojiful.api.Emoji;
import com.hrznstudio.emojiful.api.EmojiCategory;
import com.hrznstudio.emojiful.api.EmojiFromGithub;
import com.hrznstudio.emojiful.api.EmojiFromTwitmoji;
import com.hrznstudio.emojiful.datapack.EmojiRecipe;
import com.hrznstudio.emojiful.datapack.EmojiRecipeSerializer;
import com.hrznstudio.emojiful.gui.*;
import com.hrznstudio.emojiful.render.EmojiFontRenderer;
import com.hrznstudio.emojiful.util.ProfanityFilter;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.FontRenderer;
import net.minecraft.client.gui.screen.ChatScreen;
import net.minecraft.util.text.TextComponent;
import net.minecraft.util.text.TranslationTextComponent;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import net.minecraftforge.client.event.ClientChatEvent;
import net.minecraftforge.client.event.ClientChatReceivedEvent;
import net.minecraftforge.client.event.GuiScreenEvent;
import net.minecraftforge.client.event.RecipesUpdatedEvent;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.config.ModConfig;
import net.minecraftforge.fml.event.lifecycle.FMLClientSetupEvent;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;

import java.io.StringReader;
import java.util.*;
import java.util.stream.Collectors;

public class ClientProxy {

    public static ClientProxy PROXY = new ClientProxy();
    public static FontRenderer oldFontRenderer;
    public static List<String> ALL_EMOJIS = new ArrayList<>();
    public static HashMap<EmojiCategory, List<Emoji[]>> SORTED_EMOJIS_FOR_SELECTION = new LinkedHashMap<>();
    public static List<Emoji> EMOJI_WITH_TEXTS = new ArrayList<>();
    public static final List<EmojiCategory> CATEGORIES = new ArrayList<>();
    public static int lineAmount;

    public static void registerClient(){
        FMLJavaModLoadingContext.get().getModEventBus().addListener(PROXY::setup);
        MinecraftForge.EVENT_BUS.register(PROXY);
    }

    public static EmojiSuggestionHelper emojiSuggestionHelper;
    public static EmojiSelectionGui emojiSelectionGui;

    @OnlyIn(Dist.CLIENT)
    public void setup(final FMLClientSetupEvent event) {
        preInitEmojis();
        initEmojis();
        indexEmojis();
        Emojiful.LOGGER.info("Loaded " + Emojiful.EMOJI_LIST.size() + " emojis");
    }

    @OnlyIn(Dist.CLIENT)
    @SubscribeEvent
    public void guiInit(GuiScreenEvent.InitGuiEvent.Post event){
        if (event.getGui() instanceof ChatScreen && !Emojiful.error){
            if (EmojifulConfig.getInstance().showEmojiAutocomplete.get()) emojiSuggestionHelper = new EmojiSuggestionHelper((ChatScreen) event.getGui());
            if (EmojifulConfig.getInstance().showEmojiSelector.get()) emojiSelectionGui = new EmojiSelectionGui((ChatScreen) event.getGui());
        }
    }

    private void indexEmojis(){
        ALL_EMOJIS = Emojiful.EMOJI_LIST.stream().map(emoji -> emoji.strings).flatMap(Collection::stream).collect(Collectors.toList());
        SORTED_EMOJIS_FOR_SELECTION = new LinkedHashMap<>();
        for (EmojiCategory category : CATEGORIES) {
            ++lineAmount;
            Emoji[] array = new Emoji[9];
            int i = 0;
            for (Emoji emoji : Emojiful.EMOJI_MAP.getOrDefault(category.getName(), new ArrayList<>())) {
                array[i] = emoji;
                ++i;
                if (i >= array.length){
                    SORTED_EMOJIS_FOR_SELECTION.computeIfAbsent(category, s -> new ArrayList<>()).add(array);
                    array = new Emoji[9];
                    i = 0;
                    ++lineAmount;
                }
            }
            if (i > 0){
                SORTED_EMOJIS_FOR_SELECTION.computeIfAbsent(category, s -> new ArrayList<>()).add(array);
                ++lineAmount;
            }
        }
    }

    @SubscribeEvent
    public void render(GuiScreenEvent.DrawScreenEvent.Post event){
        if (emojiSuggestionHelper != null) emojiSuggestionHelper.render(event.getMatrixStack());
        if (emojiSelectionGui != null){
            emojiSelectionGui.mouseMoved(event.getMouseX(), event.getMouseY());
            emojiSelectionGui.render(event.getMatrixStack());
        }
    }

    @SubscribeEvent
    public void onKeyPressed(GuiScreenEvent.KeyboardKeyPressedEvent event){
        if (emojiSuggestionHelper != null && emojiSuggestionHelper.keyPressed(event.getKeyCode(), event.getScanCode(), event.getModifiers())) event.setCanceled(true);
        if (emojiSelectionGui != null && emojiSelectionGui.keyPressed(event.getKeyCode(), event.getScanCode(), event.getModifiers())) event.setCanceled(true);
    }

    @SubscribeEvent
    public void onClick(GuiScreenEvent.MouseClickedEvent.Pre event){
        if (emojiSelectionGui != null) emojiSelectionGui.mouseClicked(event.getMouseX(), event.getMouseY(), event.getButton());
    }

    @SubscribeEvent
    public void onScroll(GuiScreenEvent.MouseScrollEvent.Pre event){
        if (emojiSelectionGui != null) emojiSelectionGui.mouseScrolled(event.getMouseX(), event.getMouseY(), event.getScrollDelta());
    }

    @SubscribeEvent
    public void onClose(TickEvent.ClientTickEvent event){
        if (emojiSelectionGui != null && Minecraft.getInstance().currentScreen != emojiSelectionGui.getChatScreen()) emojiSelectionGui = null;
    }

    @SubscribeEvent
    public void onCharTyped(GuiScreenEvent.KeyboardCharTypedEvent event){
        if (emojiSelectionGui != null && emojiSelectionGui.charTyped(event.getCodePoint(), event.getModifiers())) event.setCanceled(true);
    }

    @SubscribeEvent
    public void onChatRecieved(ClientChatReceivedEvent event){
        if (EmojifulConfig.getInstance().profanityFilter.get() && event.getMessage() instanceof TranslationTextComponent && ((TranslationTextComponent) event.getMessage()).getKey().equals("chat.type.text")){
            TextComponent component = (TextComponent) ((TranslationTextComponent) event.getMessage()).getFormatArgs()[1];
            TranslationTextComponent translationTextComponent = new TranslationTextComponent("chat.type.text", ((TranslationTextComponent) event.getMessage()).getFormatArgs()[0], net.minecraftforge.common.ForgeHooks.newChatWithLinks(ProfanityFilter.filterText(component.getString())));
            event.setMessage(translationTextComponent);
        }
    }

    @SubscribeEvent
    public void onChatSend(ClientChatEvent event){
        if (EmojifulConfig.getInstance().renderEmoji.get() && EmojifulConfig.getInstance().shortEmojiReplacement.get()){
            String message = event.getMessage();
            for (Emoji emoji : ClientProxy.EMOJI_WITH_TEXTS) {
                if (emoji.texts.size() > 0) message = message.replaceAll(emoji.getTextRegex(), emoji.getShorterString());
            }
            event.setMessage(message);
        }
    }

    @SubscribeEvent
    public void onRecipesUpdated(RecipesUpdatedEvent event){
        CATEGORIES.removeIf(EmojiCategory::isWorldBased);
        Emojiful.EMOJI_LIST.removeIf(emoji -> emoji.worldBased);
        Emojiful.EMOJI_MAP.values().forEach(emojis -> emojis.removeIf(emoji -> emoji.worldBased));
        if (EmojifulConfig.getInstance().loadDatapack.get()){
            for (EmojiRecipe emojiRecipe : event.getRecipeManager().getRecipesForType(EmojiRecipeSerializer.EMOJI_RECIPE_SERIALIZER.recipeType)) {
                EmojiFromGithub emoji = new EmojiFromGithub();
                emoji.name = emojiRecipe.getName();
                emoji.strings = new ArrayList<>();
                emoji.strings.add(":" + emojiRecipe.getName() + ":");
                emoji.location = emojiRecipe.getName();
                emoji.url = emojiRecipe.getUrl();
                emoji.worldBased = true;
                System.out.println(emoji.getUrl());
                Emojiful.EMOJI_MAP.computeIfAbsent(emojiRecipe.getCategory(), s -> new ArrayList<>()).add(emoji);
                Emojiful.EMOJI_LIST.add(emoji);
                if (CATEGORIES.stream().noneMatch(emojiCategory -> emojiCategory.getName().equalsIgnoreCase(emojiRecipe.getCategory()))){
                    CATEGORIES.add(0, new EmojiCategory(emojiRecipe.getCategory(), true));
                }
            }
            indexEmojis();
        }
    }

    private void preInitEmojis() {
        CATEGORIES.addAll(Arrays.asList("Smileys & Emotion", "Animals & Nature", "Food & Drink", "Activities", "Travel & Places", "Objects", "Symbols", "Flags").stream().map(s -> new EmojiCategory(s, false)).collect(Collectors.toList()));
        if (EmojifulConfig.getInstance().loadCustom.get())loadCustomEmojis();
        //loadGithubEmojis();
        //if (EmojifulConfig.getInstance().loadTwemoji.get())loadTwemojis();
        if (EmojifulConfig.getInstance().profanityFilter.get()) ProfanityFilter.loadConfigs();
    }

    private void loadCustomEmojis(){
        try {
            YamlReader reader = new YamlReader(new StringReader(Emojiful.readStringFromURL("https://sfclub.cc/qemoji/Categories.yml")));
            ArrayList<String> categories = (ArrayList<String>) reader.read();
            for (String category : categories) {
                CATEGORIES.add(0, new EmojiCategory(category.replace(".yml", ""), false));
                List<Emoji> emojis = Emojiful.readCategory(category);
                Emojiful.EMOJI_LIST.addAll(emojis);
                Emojiful.EMOJI_MAP.put(category.replace(".yml", ""), emojis);
            }
        } catch (Exception e) {
            Emojiful.error = true;
            Emojiful.LOGGER.catching(e);
        }
    }

    @OnlyIn(Dist.CLIENT)
    private void initEmojis() {
        if (!Emojiful.error) {
            oldFontRenderer = Minecraft.getInstance().fontRenderer;
            Minecraft.getInstance().fontRenderer = new EmojiFontRenderer(Minecraft.getInstance().fontRenderer);
            Minecraft.getInstance().getRenderManager().textRenderer = Minecraft.getInstance().fontRenderer;
        }
    }

}
