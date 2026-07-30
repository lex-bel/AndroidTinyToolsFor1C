package com.alexkmbk.androidtinytools;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothSocket;
import android.content.Context;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Печать на мобильных принтерах этикеток (ZPL, TSPL, CPCL)
 * по Bluetooth (SPP) или по сети (TCP/IP, обычно порт 9100).
 */
public class PrinterClass {

    private static final UUID SPP_UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB");
    private static final int DEFAULT_TCP_PORT = 9100;
    private static final int DEFAULT_TIMEOUT_MS = 5000;
    // пауза, после которой ответ принтера на запрос статуса считается полученным полностью
    private static final int RESPONSE_QUIET_MS = 300;

    // Одно активное подключение на приложение: объект PrinterClass создаётся заново
    // при каждом вызове из нативного кода, поэтому состояние хранится статически.
    private static final Object lock = new Object();
    private static ExecutorService executor;
    private static Socket tcpSocket;
    private static BluetoothSocket btSocket;
    private static OutputStream outStream;
    private static InputStream inStream;
    private static int ioTimeoutMs = DEFAULT_TIMEOUT_MS;

    private final Activity mContext;

    public PrinterClass(Activity mContext) {
        this.mContext = mContext;
    }

    /**
     * Подключение к принтеру.
     *
     * @param addressType "ipaddress" (сеть) или "macaddress" (Bluetooth)
     * @param address     IP-адрес/имя хоста или MAC-адрес вида XX:XX:XX:XX:XX:XX
     * @param port        TCP-порт (для сетевого принтера), по умолчанию 9100
     * @param timeoutMs   таймаут подключения и операций ввода-вывода
     * @return JSON: {"success":Булево, "error":Строка, "type":"bluetooth"|"network"}
     */
    public String connect(String addressType, String address, int port, int timeoutMs) {

        String type = addressType == null ? "" : addressType.trim().toLowerCase();
        final String addr = address == null ? "" : address.trim();
        if (addr.isEmpty()) {
            return errorJson("Не указан адрес принтера");
        }

        final boolean bluetooth;
        if (type.equals("macaddress") || type.equals("mac") || type.equals("bluetooth") || type.equals("bt")) {
            bluetooth = true;
        } else if (type.equals("ipaddress") || type.equals("ip") || type.equals("network")
                || type.equals("tcp") || type.equals("wifi")) {
            bluetooth = false;
        } else {
            return errorJson("Неизвестный тип адреса: \"" + addressType
                    + "\". Ожидается \"ipaddress\" или \"macaddress\"");
        }

        if (bluetooth && !Utils.checkBluetoothPermissions(mContext)) {
            return errorJson("Нет разрешений Bluetooth. Разрешения запрошены у пользователя — повторите подключение.");
        }

        final int fTimeout = timeoutMs > 0 ? timeoutMs : DEFAULT_TIMEOUT_MS;
        final int fPort = port > 0 ? port : DEFAULT_TCP_PORT;

        synchronized (lock) {
            closeConnection();
            ioTimeoutMs = fTimeout;
        }

        return runOnWorker(fTimeout + 2000L, new Callable<String>() {
            @Override
            public String call() throws Exception {
                if (bluetooth) {
                    connectBluetooth(addr);
                } else {
                    connectNetwork(addr, fPort, fTimeout);
                }
                JSONObject res = new JSONObject();
                res.put("success", true);
                res.put("error", "");
                res.put("type", bluetooth ? "bluetooth" : "network");
                return res.toString();
            }
        });
    }

    /**
     * Отправка данных на печать.
     *
     * @param language "ZPL", "TSPL", "CPCL" или "" (передать как есть)
     * @param data     текст задания печати
     * @param encoding кодировка данных: UTF-8 (по умолчанию), CP866, CP1251, LATIN1 и т.п.
     * @return JSON: {"success":Булево, "error":Строка, "bytes_sent":Число}
     */
    public String print(String language, String data, String encoding) {

        if (data == null || data.isEmpty()) {
            return errorJson("Не переданы данные для печати");
        }

        final Charset charset;
        try {
            charset = resolveCharset(encoding);
        } catch (Exception e) {
            return errorJson("Неизвестная кодировка: " + encoding);
        }

        String lang = normalizeLanguage(language);
        if (lang == null) {
            return errorJson("Неизвестный язык принтера: \"" + language
                    + "\". Ожидается ZPL, TSPL или CPCL");
        }

        if (!isConnected()) {
            return errorJson("Принтер не подключен");
        }

        String payload = data;
        // TSPL и CPCL требуют перевода строки после последней команды
        if ((lang.equals("TSPL") || lang.equals("CPCL")) && !payload.endsWith("\n")) {
            payload = payload + "\r\n";
        }
        final byte[] bytes = payload.getBytes(charset);

        // запас времени на передачу больших заданий по Bluetooth (~10 КБ/с в худшем случае)
        long writeTimeout = ioTimeoutMs + bytes.length / 10L + 2000L;

        return runOnWorker(writeTimeout, new Callable<String>() {
            @Override
            public String call() throws Exception {
                OutputStream out;
                synchronized (lock) {
                    out = outStream;
                }
                if (out == null) {
                    throw new IOException("Принтер не подключен");
                }
                int offset = 0;
                while (offset < bytes.length) {
                    int len = Math.min(4096, bytes.length - offset);
                    out.write(bytes, offset, len);
                    offset += len;
                }
                out.flush();

                JSONObject res = new JSONObject();
                res.put("success", true);
                res.put("error", "");
                res.put("bytes_sent", bytes.length);
                return res.toString();
            }
        });
    }

    /** Отключение от принтера. */
    public void disconnect() {
        synchronized (lock) {
            closeConnection();
        }
    }

    /** Проверка наличия активного подключения (без обращения к принтеру). */
    public boolean isConnected() {
        synchronized (lock) {
            if (btSocket != null) {
                return btSocket.isConnected();
            }
            if (tcpSocket != null) {
                return tcpSocket.isConnected() && !tcpSocket.isClosed();
            }
            return false;
        }
    }

    /**
     * Запрос статуса принтера.
     *
     * @param language "ZPL", "TSPL", "CPCL" — язык определяет команду запроса статуса.
     *                 Пустая строка — вернуть только состояние подключения.
     * @return JSON со свойствами connected, raw и флагами состояния (зависят от языка)
     */
    public String getStatus(String language) {

        final String lang = normalizeLanguage(language);
        boolean connected = isConnected();

        if (lang == null || lang.equals("RAW") || !connected) {
            try {
                JSONObject res = new JSONObject();
                res.put("success", lang != null);
                res.put("connected", connected);
                res.put("error", lang == null ? "Неизвестный язык принтера: \"" + language + "\"" : "");
                return res.toString();
            } catch (JSONException e) {
                return errorJson(e.getMessage());
            }
        }

        return runOnWorker(ioTimeoutMs + 2000L, new Callable<String>() {
            @Override
            public String call() throws Exception {
                OutputStream out;
                InputStream in;
                synchronized (lock) {
                    out = outStream;
                    in = inStream;
                }
                if (out == null || in == null) {
                    throw new IOException("Принтер не подключен");
                }

                drainInput(in);

                byte[] query;
                if (lang.equals("ZPL")) {
                    query = "~HS".getBytes(StandardCharsets.US_ASCII);
                } else if (lang.equals("TSPL")) {
                    query = new byte[]{0x1B, '!', '?'};
                } else { // CPCL
                    query = new byte[]{0x1B, 'h'};
                }
                out.write(query);
                out.flush();

                byte[] response = readResponse(in, ioTimeoutMs);

                JSONObject res = new JSONObject();
                res.put("success", true);
                res.put("connected", true);
                res.put("language", lang);
                res.put("error", response.length == 0 ? "Принтер не ответил на запрос статуса" : "");

                if (lang.equals("ZPL")) {
                    parseZplStatus(response, res);
                } else if (lang.equals("TSPL")) {
                    parseTsplStatus(response, res);
                } else {
                    parseCpclStatus(response, res);
                }
                return res.toString();
            }
        });
    }

    // ------------------------------------------------------------------ private

    @SuppressLint("MissingPermission")
    private void connectBluetooth(String macAddress) throws IOException {

        BluetoothManager bluetoothManager =
                (BluetoothManager) mContext.getSystemService(Context.BLUETOOTH_SERVICE);
        BluetoothAdapter adapter = bluetoothManager == null ? null : bluetoothManager.getAdapter();
        if (adapter == null) {
            throw new IOException("Отсутствует поддержка Bluetooth");
        }
        if (!adapter.isEnabled()) {
            throw new IOException("Bluetooth выключен");
        }

        String mac = macAddress.toUpperCase();
        if (!BluetoothAdapter.checkBluetoothAddress(mac)) {
            throw new IOException("Некорректный MAC-адрес: " + macAddress);
        }

        BluetoothDevice device = adapter.getRemoteDevice(mac);
        BluetoothSocket socket = device.createRfcommSocketToServiceRecord(SPP_UUID);

        adapter.cancelDiscovery();

        try {
            socket.connect();
        } catch (IOException e) {
            try {
                socket.close();
            } catch (IOException ignored) {
            }
            throw new IOException("Не удалось подключиться к принтеру " + mac + ": " + e.getMessage());
        }

        synchronized (lock) {
            btSocket = socket;
            outStream = socket.getOutputStream();
            inStream = socket.getInputStream();
        }
    }

    private void connectNetwork(String host, int port, int timeoutMs) throws IOException {

        Socket socket = new Socket();
        try {
            socket.connect(new InetSocketAddress(host, port), timeoutMs);
            socket.setSoTimeout(timeoutMs);
            socket.setTcpNoDelay(true);
        } catch (IOException e) {
            try {
                socket.close();
            } catch (IOException ignored) {
            }
            throw new IOException("Не удалось подключиться к " + host + ":" + port + ": " + e.getMessage());
        }

        synchronized (lock) {
            tcpSocket = socket;
            outStream = socket.getOutputStream();
            inStream = socket.getInputStream();
        }
    }

    /**
     * Выполняет операцию с сокетом в рабочем потоке (сеть на главном потоке запрещена).
     * При таймауте принудительно закрывает соединение — это разблокирует зависшую операцию.
     */
    private String runOnWorker(long timeoutMs, Callable<String> task) {

        Future<String> future;
        synchronized (lock) {
            if (executor == null || executor.isShutdown()) {
                executor = Executors.newSingleThreadExecutor();
            }
            future = executor.submit(task);
        }

        try {
            return future.get(timeoutMs, TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            future.cancel(true);
            synchronized (lock) {
                closeConnection();
            }
            return errorJson("Таймаут операции (" + timeoutMs + " мс)");
        } catch (ExecutionException e) {
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            synchronized (lock) {
                closeConnection();
            }
            String message = cause.getMessage();
            return errorJson(message == null || message.isEmpty() ? cause.toString() : message);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return errorJson("Операция прервана");
        }
    }

    // вызывается только под lock
    private static void closeConnection() {
        if (outStream != null) {
            try {
                outStream.close();
            } catch (IOException ignored) {
            }
            outStream = null;
        }
        if (inStream != null) {
            try {
                inStream.close();
            } catch (IOException ignored) {
            }
            inStream = null;
        }
        if (btSocket != null) {
            try {
                btSocket.close();
            } catch (IOException ignored) {
            }
            btSocket = null;
        }
        if (tcpSocket != null) {
            try {
                tcpSocket.close();
            } catch (IOException ignored) {
            }
            tcpSocket = null;
        }
    }

    private static void drainInput(InputStream in) throws IOException {
        byte[] chunk = new byte[512];
        while (in.available() > 0) {
            if (in.read(chunk) < 0) {
                break;
            }
        }
    }

    private static byte[] readResponse(InputStream in, int timeoutMs) throws IOException {

        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        byte[] chunk = new byte[512];
        long deadline = System.currentTimeMillis() + timeoutMs;
        long quietDeadline = deadline;

        while (true) {
            long now = System.currentTimeMillis();
            if (now >= deadline || (buffer.size() > 0 && now >= quietDeadline)) {
                break;
            }
            int available = in.available();
            if (available > 0) {
                int n = in.read(chunk, 0, Math.min(chunk.length, available));
                if (n < 0) {
                    break;
                }
                buffer.write(chunk, 0, n);
                quietDeadline = System.currentTimeMillis() + RESPONSE_QUIET_MS;
            } else {
                try {
                    Thread.sleep(50);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }
        return buffer.toByteArray();
    }

    /**
     * Ответ ZPL на ~HS: три строки, каждая обрамлена STX/ETX.
     * Строка 1: aaa,b,c,dddd,... где b — нет бумаги, c — пауза.
     * Строка 2: mmm,n,o,p,... где o — открыта крышка, p — закончился риббон.
     */
    private static void parseZplStatus(byte[] response, JSONObject res) throws JSONException {

        String text = new String(response, StandardCharsets.US_ASCII);
        res.put("raw", text.replace("\u0002", "").replace("\u0003", "").trim());

        String[] lines = text.split("[\\u0002\\u0003\\r\\n]+");
        int lineIndex = 0;
        for (String line : lines) {
            if (line.trim().isEmpty()) {
                continue;
            }
            String[] fields = line.trim().split(",");
            if (lineIndex == 0 && fields.length > 2) {
                res.put("paper_out", "1".equals(fields[1]));
                res.put("paused", "1".equals(fields[2]));
            } else if (lineIndex == 1 && fields.length > 3) {
                res.put("head_open", "1".equals(fields[2]));
                res.put("ribbon_out", "1".equals(fields[3]));
            }
            lineIndex++;
        }
        res.put("ready", !res.optBoolean("paper_out", false)
                && !res.optBoolean("paused", false)
                && !res.optBoolean("head_open", false));
    }

    /** Ответ TSPL на ESC !? — один байт состояния (0x00 — принтер готов). */
    private static void parseTsplStatus(byte[] response, JSONObject res) throws JSONException {

        if (response.length < 1) {
            return;
        }
        int status = response[0] & 0xFF;
        res.put("raw", String.format("%02X", status));
        res.put("head_open", (status & 0x01) != 0);
        res.put("paper_jam", (status & 0x02) != 0);
        res.put("paper_out", (status & 0x04) != 0);
        res.put("ribbon_out", (status & 0x08) != 0);
        res.put("paused", (status & 0x10) != 0);
        res.put("printing", (status & 0x20) != 0);
        res.put("ready", status == 0);
    }

    /**
     * Ответ CPCL на ESC h — один байт состояния (Zebra):
     * бит 0 — занят (идёт печать), бит 1 — нет бумаги, бит 2 — пауза.
     * У других производителей значения битов могут отличаться — используйте поле raw.
     */
    private static void parseCpclStatus(byte[] response, JSONObject res) throws JSONException {

        if (response.length < 1) {
            return;
        }
        int status = response[0] & 0xFF;
        res.put("raw", String.format("%02X", status));
        res.put("busy", (status & 0x01) != 0);
        res.put("paper_out", (status & 0x02) != 0);
        res.put("paused", (status & 0x04) != 0);
        res.put("ready", status == 0);
    }

    private static String normalizeLanguage(String language) {

        if (language == null) {
            return "RAW";
        }
        String lang = language.trim().toUpperCase();
        if (lang.isEmpty() || lang.equals("RAW")) {
            return "RAW";
        }
        if (lang.equals("ZPL") || lang.equals("ZPL2") || lang.equals("ZPLII") || lang.equals("ZPL II")) {
            return "ZPL";
        }
        if (lang.equals("TSPL") || lang.equals("TSPL2") || lang.equals("TSC")) {
            return "TSPL";
        }
        if (lang.equals("CPCL")) {
            return "CPCL";
        }
        return null;
    }

    private static Charset resolveCharset(String encoding) {

        if (encoding == null) {
            return StandardCharsets.UTF_8;
        }
        String name = encoding.trim().toUpperCase();
        if (name.isEmpty() || name.equals("UTF8") || name.equals("UTF-8")) {
            return StandardCharsets.UTF_8;
        }
        if (name.equals("CP866") || name.equals("866") || name.equals("IBM866") || name.equals("DOS866")) {
            return Charset.forName("IBM866");
        }
        if (name.equals("CP1251") || name.equals("1251") || name.equals("WINDOWS-1251")
                || name.equals("WIN1251") || name.equals("ANSI")) {
            return Charset.forName("windows-1251");
        }
        if (name.equals("LATIN1") || name.equals("ISO-8859-1") || name.equals("ASCII")) {
            return StandardCharsets.ISO_8859_1;
        }
        return Charset.forName(encoding.trim());
    }

    private static String errorJson(String message) {
        try {
            JSONObject res = new JSONObject();
            res.put("success", false);
            res.put("error", message == null ? "" : message);
            return res.toString();
        } catch (JSONException e) {
            return "{\"success\":false,\"error\":\"internal error\"}";
        }
    }
}
