package PhotoBot;

import com.pengrad.telegrambot.TelegramBot;
import com.pengrad.telegrambot.model.*;
import com.pengrad.telegrambot.request.GetFile;
import com.pengrad.telegrambot.request.GetUpdates;
import com.pengrad.telegrambot.request.SendMessage;

import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

import java.io.*;
import java.net.Socket;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class PhotoBot {

    private static final String BOT_TOKEN;
    private static final String INPUT_FOLDER;
    private static final String EXCEL_PATH;
    private static final String PYTHON;
    private static final String MODEL_PATH;
    private static final String PROMPT_TEXT;
    private static String currentDate = null;
    private static long currentChatId = 0;
    private static int photoCountForDate = 0;

    private static final Set<String> processedFileIds = new HashSet<>();

    private static Workbook workbook;
    private static Sheet sheet;
    private static int excelRow = 0;

    static {
        try (InputStream in = PhotoBot.class
                .getClassLoader()
                .getResourceAsStream("config.properties")) {

            Properties p = new Properties();
            p.load(in);

            BOT_TOKEN = p.getProperty("bot.token");
            INPUT_FOLDER = p.getProperty("input.folder");
            EXCEL_PATH = p.getProperty("excel.path", "report.xlsx");
            PYTHON = p.getProperty("python");
            PROMPT_TEXT = p.getProperty("prompt.text");
            MODEL_PATH = p.getProperty("model.path");

            initExcel();

        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
    static Process modelProcess;

    private static final String MODEL_SERVER = getResourcePath("python/scripts/model_server.py");
    private static final String CLASSIFY_CLIENT = getResourcePath("python/scripts/classify_client.py");

    public static void main(String[] args) throws Exception {


        // запуск Python AI сервера
        ProcessBuilder pbModel = new ProcessBuilder(PYTHON, MODEL_SERVER, MODEL_PATH, PROMPT_TEXT);
        pbModel.redirectErrorStream(true); // объединяем stdout и stderr
        modelProcess = pbModel.start();

        // shutdown hook, чтобы останавливать Python при выходе
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            System.out.println("⛔ Останавливаем AI сервер");
            modelProcess.destroy();
        }));

        // читаем вывод Python в отдельном потоке
        new Thread(() -> {
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(modelProcess.getInputStream(), StandardCharsets.UTF_8))) {

                String line;
                while ((line = reader.readLine()) != null) {
                    System.out.println("[PYTHON] " + line);
                }

            } catch (IOException e) {
                e.printStackTrace();
            }
        }).start();

        // теперь можно дождаться READY от сервера
        waitForServer();

        System.out.println("✅ AI сервер готов, продолжаем работу бота");

        TelegramBot bot = new TelegramBot(BOT_TOKEN);
        System.out.println("✅ PhotoBot запущен");

        int offset = 0;

        while (true) {

            List<Update> updates = bot.execute(
                    new GetUpdates().limit(100).offset(offset)
            ).updates();

            if (updates == null || updates.isEmpty()) {
                Thread.sleep(1500);
                continue;
            }

            for (Update update : updates) {

                offset = update.updateId() + 1;

                Message msg = update.message();
                if (msg == null) continue;

                currentChatId = msg.chat().id();

                // текст с датой
                if (msg.text() != null) {

                    String newDate = extractDate(msg.text());

                    if (newDate != null && !newDate.equals(currentDate)) {

                        if (currentDate != null) {
                            sendSummary(bot);
                        }

                        currentDate = newDate;
                        photoCountForDate = 0;

                        System.out.println("📅 Новая дата: " + currentDate);
                    }
                }

                // фото
                if (msg.photo() != null && msg.photo().length > 0) {

                    if (currentDate == null) {
                        System.out.println("⚠ Фото без даты — пропущено");
                        continue;
                    }

                    PhotoSize largest = msg.photo()[msg.photo().length - 1];
                    processPhoto(bot, largest);
                }
            }
        }
    }

    private static void processPhoto(TelegramBot bot, PhotoSize photo) {

        try {

            String fileId = photo.fileId();
            if (!processedFileIds.add(fileId)) return;

            String remotePath = bot.execute(new GetFile(fileId))
                    .file().filePath();

            photoCountForDate++;

            String baseName = currentDate + "_" + photoCountForDate;
            Path target = Paths.get(INPUT_FOLDER, baseName + ".jpg");

            Files.createDirectories(target.getParent());

            download(remotePath, target);

            // создаём строку Excel
            Row row = writeToExcel(baseName);

            // запускаем Python классификатор
            ProcessBuilder pb = new ProcessBuilder(
                    PYTHON,
                    CLASSIFY_CLIENT,
                    target.toString()
            );

            Process p = pb.start();

            BufferedReader reader = new BufferedReader(
                    new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8)
            );

            String result = reader.readLine();
            p.waitFor();

            if (result == null) result = "unknown";

            row.createCell(1).setCellValue(result);

            System.out.println("💾 " + baseName + " → " + result);

        } catch (Exception e) {
            System.err.println("❌ Фото: " + e.getMessage());
        }
    }

    private static void sendSummary(TelegramBot bot) {

        String msg = currentDate + " сохранено " + photoCountForDate + " фото";

        bot.execute(new SendMessage(currentChatId, msg));

        saveExcel();

        System.out.println("📤 " + msg);
    }

    // Excel

    private static void initExcel() {

        workbook = new XSSFWorkbook();
        sheet = workbook.createSheet("Photos");
    }

    private static Row writeToExcel(String name) {

        Row row = sheet.createRow(excelRow++);

        row.createCell(0).setCellValue(name);

        return row;
    }

    private static void saveExcel() {

        try (FileOutputStream out = new FileOutputStream(EXCEL_PATH)) {

            workbook.write(out);

        } catch (Exception e) {

            e.printStackTrace();
        }
    }

    // utils

    private static void download(String remotePath, Path localPath) throws Exception {

        String url = "https://api.telegram.org/file/bot" + BOT_TOKEN + "/" + remotePath;

        try (InputStream in = new java.net.URL(url).openStream()) {

            Files.copy(in, localPath, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static String extractDate(String text) {

        Matcher m = Pattern
                .compile("(\\b(\\d{1,2}\\.\\d{1,2}\\.\\d{4})\\b)")
                .matcher(text);

        return m.find() ? m.group(1) : null;
    }
    private static void waitForServer() {

        System.out.println("⏳ Ожидание запуска AI сервера...");

        while (true) {
            try (Socket socket = new Socket("127.0.0.1", 5000)) {

                OutputStream out = socket.getOutputStream();
                InputStream in = socket.getInputStream();

                String ping = "{\"cmd\":\"ping\"}";
                out.write(ping.getBytes());
                out.flush();

                byte[] buffer = new byte[1024];
                int len = in.read(buffer);

                String response = new String(buffer, 0, len);

                if ("READY".equals(response)) {
                    System.out.println("🤖 AI сервер готов");
                    return;
                }

            } catch (Exception e) {

                try {
                    Thread.sleep(2000);
                } catch (InterruptedException ignored) {}

            }
        }
    }
    private static String getResourcePath(String resource) {
        try {
            URL url = PhotoBot.class
                    .getClassLoader()
                    .getResource(resource);

            if (url == null) {
                throw new RuntimeException("Resource not found: " + resource);
            }

            return Paths.get(url.toURI()).toString();
        } catch (Exception e) {
            throw new RuntimeException("Resource not found: " + resource, e);
        }
    }
}