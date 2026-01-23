package ZZZ.ZZZ;

import com.google.common.collect.BiMap;
import com.google.common.collect.HashBiMap;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextComponent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class ZZZ extends JavaPlugin implements Listener {

    private TextComponent warningSymbol = Component.text("⚠")
            .color(NamedTextColor.YELLOW)
            .hoverEvent(HoverEvent.showText(
                    Component.text("Раскладка была изменена")
                            .color(NamedTextColor.GOLD)
            ));

    private LangTyposConverter converter;

    // Система защиты от спама
    private final Map<UUID, Integer> messageCount = new HashMap<>();
    private final Map<UUID, Long> lastMessageTime = new HashMap<>();
    private final Map<UUID, Long> spamBlockedUntil = new HashMap<>();

    // Конфигурация защиты от спама
    private static final int MAX_MESSAGES = 4; // Максимальное количество сообщений
    private static final long TIME_WINDOW_MS = 2000; // 2 секунд для подсчета сообщений
    private static final long BLOCK_DURATION_MS = 20000; // 20 секунд блокировки

    @Override
    public void onEnable() {
        // Инициализируем конвертер
        converter = new LangTyposConverter();
        converter.loadDictionaries();

        // Регистрируем события
        getServer().getPluginManager().registerEvents(this, this);

        // Запускаем очистку старых данных каждые 5 минут
        new BukkitRunnable() {
            @Override
            public void run() {
                cleanOldData();
            }
        }.runTaskTimer(this, 6000L, 6000L); // Каждые 5 минут

        getLogger().info("Плагин ZZZ включен");
    }

    @Override
    public void onDisable() {
        // Очищаем все данные
        messageCount.clear();
        lastMessageTime.clear();
        spamBlockedUntil.clear();
        getLogger().info("Плагин ZZZ выключен");
    }

    @EventHandler
    public void onPlayerChat(AsyncPlayerChatEvent event) {
        Player player = event.getPlayer();
        UUID playerId = player.getUniqueId();
        String originalMessage = event.getMessage();

        // Проверяем, не заблокирован ли игрок за спам
        if (isPlayerBlocked(playerId)) {
            event.setCancelled(true);
            return;
        }

        // Обновляем счетчик сообщений
        updateMessageCount(playerId);

        // Проверяем на спам
        if (checkForSpam(playerId)) {
            blockPlayer(playerId);
            event.setCancelled(true);
            new BukkitRunnable() {
                @Override
                public void run() {
                }
            }.runTask(this);
            return;
        }

        // Автоисправление раскладки
        String correctedMessage = converter.convertString(originalMessage);

        // Сравниваем, было ли сообщение изменено
        if (!originalMessage.equals(correctedMessage)) {
            // Если сообщение было изменено, отменяем оригинальное событие
            event.setCancelled(true);

            // Используем синхронный таск для отправки сообщения
            new BukkitRunnable() {
                @Override
                public void run() {
                    // Получаем формат сообщения из события
                    String format = event.getFormat();

                    // Заменяем имя игрока и сообщение в формате
                    String formattedMessage = String.format(
                            format,
                            player.getDisplayName(),
                            correctedMessage
                    );

                    // Создаем компонент с предупреждающим символом
                    TextComponent finalMessage = Component.text()
                            .append(warningSymbol)
                            .append(Component.text(" "))
                            .append(Component.text(formattedMessage))
                            .build();

                    // Отправляем сообщение всем получателям
                    for (Player recipient : event.getRecipients()) {
                        recipient.sendMessage(finalMessage);
                    }
                }
            }.runTask(this);
        }
        // Если сообщение не было изменено, оставляем стандартную обработку
    }

    /**
     * Обновляет счетчик сообщений для игрока
     */
    private void updateMessageCount(UUID playerId) {
        long currentTime = System.currentTimeMillis();

        // Удаляем старые записи, если прошло больше TIME_WINDOW_MS
        if (lastMessageTime.containsKey(playerId)) {
            long lastTime = lastMessageTime.get(playerId);
            if (currentTime - lastTime > TIME_WINDOW_MS) {
                // Сбрасываем счетчик, если прошло больше окна времени
                messageCount.put(playerId, 1);
            } else {
                // Увеличиваем счетчик
                int count = messageCount.getOrDefault(playerId, 0) + 1;
                messageCount.put(playerId, count);
            }
        } else {
            // Первое сообщение
            messageCount.put(playerId, 1);
        }

        // Обновляем время последнего сообщения
        lastMessageTime.put(playerId, currentTime);
    }

    /**
     * Проверяет, не отправляет ли игрок сообщения слишком быстро
     */
    private boolean checkForSpam(UUID playerId) {
        int count = messageCount.getOrDefault(playerId, 0);
        return count > MAX_MESSAGES;
    }

    /**
     * Проверяет, заблокирован ли игрок
     */
    private boolean isPlayerBlocked(UUID playerId) {
        if (!spamBlockedUntil.containsKey(playerId)) {
            return false;
        }

        long blockUntil = spamBlockedUntil.get(playerId);
        long currentTime = System.currentTimeMillis();

        // Если время блокировки истекло, удаляем запись
        if (currentTime > blockUntil) {
            spamBlockedUntil.remove(playerId);
            return false;
        }

        return true;
    }

    /**
     * Блокирует игрока на определенное время
     */
    private void blockPlayer(UUID playerId) {
        long blockUntil = System.currentTimeMillis() + BLOCK_DURATION_MS;
        spamBlockedUntil.put(playerId, blockUntil);

        // Сбрасываем счетчик сообщений после блокировки
        messageCount.remove(playerId);
        lastMessageTime.remove(playerId);
    }

    /**
     * Очищает старые данные для экономии памяти
     */
    private void cleanOldData() {
        long currentTime = System.currentTimeMillis();
        long cleanTime = currentTime - TIME_WINDOW_MS * 2; // Удаляем данные старше 2х окон

        // Очищаем старые записи о сообщениях
        Iterator<Map.Entry<UUID, Long>> iterator = lastMessageTime.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<UUID, Long> entry = iterator.next();
            if (entry.getValue() < cleanTime) {
                UUID playerId = entry.getKey();
                iterator.remove();
                messageCount.remove(playerId);
            }
        }

        // Очищаем истекшие блокировки
        Iterator<Map.Entry<UUID, Long>> blockIterator = spamBlockedUntil.entrySet().iterator();
        while (blockIterator.hasNext()) {
            Map.Entry<UUID, Long> entry = blockIterator.next();
            if (entry.getValue() < currentTime) {
                blockIterator.remove();
            }
        }
        
    }
}

/**
 * Класс для исправления раскладки текста содержащего русские и английские слова.
 * Использует проверку по словарям и алгоритм стемминга Портера
 */
class LangTyposConverter {

    BiMap<Character, Character> enToRusBiMap;
    BiMap<Character, Character> rusToEnBiMap;

    HashSet<String> russianDictionary;
    HashSet<String> englishDictionary;

    HashSet<String> morFormRussianDictionary;

    StringBuilder resultMessage;
    StringBuilder resultWord;

    public LangTyposConverter() {
        enToRusBiMap = HashBiMap.create();
        enToRusBiMap.put('`', 'ё');
        enToRusBiMap.put('q', 'й');
        enToRusBiMap.put('w', 'ц');
        enToRusBiMap.put('e', 'у');
        enToRusBiMap.put('r', 'к');
        enToRusBiMap.put('t', 'е');
        enToRusBiMap.put('y', 'н');
        enToRusBiMap.put('u', 'г');
        enToRusBiMap.put('i', 'ш');
        enToRusBiMap.put('o', 'щ');
        enToRusBiMap.put('p', 'з');
        enToRusBiMap.put('[', 'х');
        enToRusBiMap.put(']', 'ъ');
        enToRusBiMap.put('a', 'ф');
        enToRusBiMap.put('s', 'ы');
        enToRusBiMap.put('d', 'в');
        enToRusBiMap.put('f', 'а');
        enToRusBiMap.put('g', 'п');
        enToRusBiMap.put('h', 'р');
        enToRusBiMap.put('j', 'о');
        enToRusBiMap.put('k', 'л');
        enToRusBiMap.put('l', 'д');
        enToRusBiMap.put(';', 'ж');
        enToRusBiMap.put('\'', 'э');
        enToRusBiMap.put('z', 'я');
        enToRusBiMap.put('x', 'ч');
        enToRusBiMap.put('c', 'с');
        enToRusBiMap.put('v', 'м');
        enToRusBiMap.put('b', 'и');
        enToRusBiMap.put('n', 'т');
        enToRusBiMap.put('m', 'ь');
        enToRusBiMap.put(',', 'б');
        enToRusBiMap.put('.', 'ю');
        enToRusBiMap.put('/', '.');
        enToRusBiMap.put('~', 'Ё');
        enToRusBiMap.put('@', '"');
        enToRusBiMap.put('#', '№');
        enToRusBiMap.put('$', ';');
        enToRusBiMap.put('^', ':');
        enToRusBiMap.put('&', '?');
        enToRusBiMap.put('|', '/');
        enToRusBiMap.put('Q', 'Й');
        enToRusBiMap.put('W', 'Ц');
        enToRusBiMap.put('E', 'У');
        enToRusBiMap.put('R', 'К');
        enToRusBiMap.put('T', 'Е');
        enToRusBiMap.put('Y', 'Н');
        enToRusBiMap.put('U', 'Г');
        enToRusBiMap.put('I', 'Ш');
        enToRusBiMap.put('O', 'Щ');
        enToRusBiMap.put('P', 'З');
        enToRusBiMap.put('{', 'Х');
        enToRusBiMap.put('}', 'Ъ');
        enToRusBiMap.put('A', 'Ф');
        enToRusBiMap.put('S', 'Ы');
        enToRusBiMap.put('D', 'В');
        enToRusBiMap.put('F', 'А');
        enToRusBiMap.put('G', 'П');
        enToRusBiMap.put('H', 'Р');
        enToRusBiMap.put('J', 'О');
        enToRusBiMap.put('K', 'Л');
        enToRusBiMap.put('L', 'Д');
        enToRusBiMap.put(':', 'Ж');
        enToRusBiMap.put('"', 'Э');
        enToRusBiMap.put('Z', 'Я');
        enToRusBiMap.put('X', 'Ч');
        enToRusBiMap.put('C', 'С');
        enToRusBiMap.put('V', 'М');
        enToRusBiMap.put('B', 'И');
        enToRusBiMap.put('N', 'Т');
        enToRusBiMap.put('M', 'Ь');
        enToRusBiMap.put('<', 'Б');
        enToRusBiMap.put('>', 'Ю');
        enToRusBiMap.put('?', ',');
        enToRusBiMap.put(' ', ' ');

        rusToEnBiMap = enToRusBiMap.inverse();

        resultMessage = new StringBuilder();
        resultWord = new StringBuilder();
    }

    public void loadDictionaries() {
        russianDictionary = new HashSet<>();
        englishDictionary = new HashSet<>();

        // Используем ClassLoader текущего класса для загрузки ресурсов
        ClassLoader classLoader = getClass().getClassLoader();

        try (BufferedReader br = new BufferedReader(new InputStreamReader(
                Objects.requireNonNull(classLoader.getResourceAsStream("rusDict-new.txt"))))) {

            String line;
            while ((line = br.readLine()) != null) {
                russianDictionary.add(line.trim());
            }
            System.out.println("Loaded " + russianDictionary.size() + " Russian words");
        } catch (IOException e) {
            e.printStackTrace();
        }

        try (BufferedReader br = new BufferedReader(new InputStreamReader(
                Objects.requireNonNull(classLoader.getResourceAsStream("english.txt"))))) {

            String line;
            while ((line = br.readLine()) != null) {
                englishDictionary.add(line.trim());
            }
            System.out.println("Loaded " + englishDictionary.size() + " English words");
        } catch (IOException e) {
            e.printStackTrace();
        }

        morFormRussianDictionary = new HashSet<>();
        for (String key : russianDictionary) {
            morFormRussianDictionary.add(Porter.stem(key));
        }
    }

    public String mirrorLayout(String string) {
        resultWord.delete(0, resultWord.length());
        for (int i = 0; i < string.length(); i++) {
            if (enToRusBiMap.containsKey(string.charAt(i))) {
                resultWord.append(enToRusBiMap.get(string.charAt(i)));
            } else if (rusToEnBiMap.containsKey(string.charAt(i))) {
                resultWord.append(rusToEnBiMap.get(string.charAt(i)));
            } else {
                resultWord.append(string.charAt(i));
            }
        }

        return resultWord.toString();
    }

    public String convertString(String message) {
        resultMessage.delete(0, resultMessage.length());

        for (String word : message.split(" ")) {
            String revLayout = mirrorLayout(word);

            if (russianDictionary.contains(word.toLowerCase()) ||
                    englishDictionary.contains(word.toLowerCase()) ||
                    englishDictionary.contains(word)) {
                resultMessage.append(word);
            } else if (russianDictionary.contains(revLayout.toLowerCase()) ||
                    englishDictionary.contains(revLayout.toLowerCase()) ||
                    englishDictionary.contains(revLayout)) {
                resultMessage.append(revLayout);
            } else {
                if (!revLayout.matches("(\\p{L1})*")) {
                    if (morFormRussianDictionary.contains(Porter.stem(revLayout.replaceAll("(\\p{P}*)", "")))) {
                        resultMessage.append(revLayout);
                    } else {
                        resultMessage.append(word);
                    }
                } else {
                    resultMessage.append(word);
                }
            }

            resultMessage.append(" ");
        }

        return resultMessage.toString().trim();
    }
}

class Porter {

    private static final Pattern PERFECTIVEGROUND = Pattern.compile("((ив|ивши|ившись|ыв|ывши|ывшись)|((?<=[ая])(в|вши|вшись)))$");
    private static final Pattern REFLEXIVE = Pattern.compile("(с[яь])$");
    private static final Pattern ADJECTIVE = Pattern.compile("(ее|ие|ые|ое|ими|ыми|ей|ий|ый|ой|ем|им|ым|ом|его|ого|ему|ому|их|ых|ую|юю|ая|яя|ою|ею)$");
    private static final Pattern PARTICIPLE = Pattern.compile("((ивш|ывш|ующ)|((?<=[ая])(ем|нн|вш|ющ|щ)))$");
    private static final Pattern VERB = Pattern.compile("((ила|ыла|ена|ейте|уйте|ите|или|ыли|ей|уй|ил|ыл|им|ым|ен|ило|ыло|ено|ят|ует|уют|ит|ыт|ены|ить|ыть|ишь|ую|ю)|((?<=[ая])(ла|на|ете|йте|ли|й|л|ем|н|ло|но|ет|ют|ны|ть|ешь|нно)))$");
    private static final Pattern NOUN = Pattern.compile("(а|ев|ов|ие|ье|е|иями|ями|ами|еи|ии|и|ией|ей|ой|ий|й|иям|ям|ием|ем|ам|ом|о|у|ах|иях|ях|ы|ь|ию|ью|ю|ия|ья|я)$");
    private static final Pattern RVRE = Pattern.compile("^(.*?[аеиоуыэюя])(.*)$");
    private static final Pattern DERIVATIONAL = Pattern.compile(".*[^аеиоуыэюя]+[аеиоуыэюя].*ость?$");
    private static final Pattern DER = Pattern.compile("ость?$");
    private static final Pattern SUPERLATIVE = Pattern.compile("(ейше|ейш)$");

    private static final Pattern I = Pattern.compile("и$");
    private static final Pattern P = Pattern.compile("ь$");
    private static final Pattern NN = Pattern.compile("нн$");

    public static String stem(String word) {
        word = word.toLowerCase();
        word = word.replace('ё', 'е');
        Matcher m = RVRE.matcher(word);
        if (m.matches()) {
            String pre = m.group(1);
            String rv = m.group(2);
            String temp = PERFECTIVEGROUND.matcher(rv).replaceFirst("");
            if (temp.equals(rv)) {
                rv = REFLEXIVE.matcher(rv).replaceFirst("");
                temp = ADJECTIVE.matcher(rv).replaceFirst("");
                if (!temp.equals(rv)) {
                    rv = temp;
                    rv = PARTICIPLE.matcher(rv).replaceFirst("");
                } else {
                    temp = VERB.matcher(rv).replaceFirst("");
                    if (temp.equals(rv)) {
                        rv = NOUN.matcher(rv).replaceFirst("");
                    } else {
                        rv = temp;
                    }
                }

            } else {
                rv = temp;
            }

            rv = I.matcher(rv).replaceFirst("");

            if (DERIVATIONAL.matcher(rv).matches()) {
                rv = DER.matcher(rv).replaceFirst("");
            }

            temp = P.matcher(rv).replaceFirst("");
            if (temp.equals(rv)) {
                rv = SUPERLATIVE.matcher(rv).replaceFirst("");
                rv = NN.matcher(rv).replaceFirst("н");
            }else{
                rv = temp;
            }
            word = pre + rv;

        }

        return word;
    }
}