package com.zzz.wifiview;

import android.content.Context;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import java.io.BufferedReader;
import java.io.DataOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class ReadFile {

    @SuppressWarnings("UnusedAssignment")
    int mode = 0;
    ArrayList<Map<String, String>> list = new ArrayList<>();

    private static final Pattern SSID_PAT = Pattern.compile("<string name=\"SSID\">&quot;([^&]*)&quot;</string>");
    private static final Pattern PSK_PAT = Pattern.compile("<string name=\"PreSharedKey\">&quot;([^&]*)&quot;</string>");
    private static final Pattern KEYMGMT_PAT = Pattern.compile("<string name=\"ConfigKey\">&quot;([^&]*)&quot;([^<]*)</string>");

    public ReadFile(String path, int i) {
        mode = i;

        // Android 8.0+ 使用优化的 grep 方法
        if (android.os.Build.VERSION.SDK_INT >= 26) {
            readWithGrep(path);
        } else {
            // Android 7.0及以下使用原有方法
            readOriginal(path);
        }
    }

    // Android 8.0+ 专用的 grep 读取方法
    private void readWithGrep(String path) {
        try {
            // 直接读取整个文件然后解析
            String content = readFullFileWithSu(path);

            if (content.isEmpty()) {
                exceptionCatch("Empty content from: " + path);
                return;
            }

            // 使用原有的 scan 方法解析（保持兼容性）
            scan(content);

        } catch (Exception e) {
            exceptionCatch("readWithGrep failed: " + e.getMessage());
        }
    }

    // 使用 su 读取整个文件
    private String readFullFileWithSu(String path) throws IOException, InterruptedException {
        Process process = null;
        BufferedReader br = null;
        StringBuilder content = new StringBuilder();

        try {
            // 修正路径
            String filePath = getCorrectPath(path);

            String[] cmd = {"su", "-c", "cat \"" + filePath + "\""};
            process = Runtime.getRuntime().exec(cmd);
            br = new BufferedReader(new InputStreamReader(process.getInputStream()));

            char[] buffer = new char[8192];
            int charsRead;
            while ((charsRead = br.read(buffer)) != -1) {
                content.append(buffer, 0, charsRead);
            }

            int exitCode = process.waitFor();
            if (exitCode != 0) {
                throw new IOException("cat failed with exit code: " + exitCode);
            }

        } finally {
            if (br != null) br.close();
            if (process != null) process.destroy();
        }

        return content.toString();
    }

    // 获取正确的文件路径
    private String getCorrectPath(String originalPath) {
        // 如果是 Android 30+，修正路径
        if (android.os.Build.VERSION.SDK_INT >= 30) {
            if (originalPath.contains("/data/misc/wifi/WifiConfigStore.xml")) {
                return "/data/misc/apexdata/com.android.wifi/WifiConfigStore.xml";
            }
        }
        return originalPath;
    }

    // Android 7.0及以下的原有读取方法
    private void readOriginal(String path) {
        Process process = null;
        StringBuilder sBuilder = new StringBuilder();
        DataOutputStream os = null;
        BufferedReader br = null;

        try {
            process = Runtime.getRuntime().exec("su");
            os = new DataOutputStream(process.getOutputStream());
            os.writeBytes("cat " + path + "\n");
            os.writeBytes("exit\n");
            os.flush();
            br = new BufferedReader(new InputStreamReader(process.getInputStream()));

            char[] buffer = new char[8192];
            int charsRead;
            while ((charsRead = br.read(buffer)) != -1) {
                sBuilder.append(buffer, 0, charsRead);
            }

            // 解析内容
            scan(sBuilder.toString());

        } catch (IOException e) {
            exceptionCatch("readOriginal: " + e.getMessage());
        } finally {
            try {
                if (os != null) os.close();
                if (br != null) br.close();
                if (process != null) process.destroy();
            } catch (Exception e) {
                exceptionCatch("readOriginal finally: " + e.getMessage());
            }
        }
    }

    /* 原有的解析方法（用于 Android 7.0 及以下） */
    private void scan(String s) {
        if (android.os.Build.VERSION.SDK_INT >= 26) {
            // 这部分代码在 Android 7.0 及以下不会执行，保留以防万一
            Pattern pattern = Pattern.compile("<Network>\\n([\\s\\S]+?)\\n</Network>");
            Matcher matcher = pattern.matcher(s);
            while (matcher.find()) {
                String block = s.substring(matcher.start(), matcher.end());
                addOreo(block);
            }
        } else {
            Pattern pattern1 = Pattern.compile("network=\\{\\n([\\s\\S]+?)\\n\\}");
            Matcher matcher1 = pattern1.matcher(s);
            while (matcher1.find()) {
                String block = s.substring(matcher1.start(), matcher1.end());
                add(block);
            }
        }
    }

    /* Android 8.0 之前的解析 WiFi 方法*/
    private void add(String s) {
        HashMap<String, String> map = new HashMap<>(4);
        map.put("view", s);

        String ssid = "";
        String psk = "";
        String key_mgmt = "";

        int start = 0;
        int length = s.length();

        while (start < length) {
            int end = s.indexOf('\n', start);
            if (end == -1) end = length;

            String line = s.substring(start, end).trim();
            start = end + 1;

            if (line.isEmpty()) continue;

            int equalsIndex = line.indexOf('=');
            if (equalsIndex != -1) {
                String key = line.substring(0, equalsIndex).trim();
                String value = line.substring(equalsIndex + 1).trim();

                if (key.contains("ssid")) {
                    if (!key.contains("scan_ssid")) {
                        ssid = value;
                        if (ssid.startsWith("\"") && ssid.endsWith("\"")) {
                            ssid = ssid.substring(1, ssid.length() - 1);
                        } else {
                            ssid = toUTF8(ssid);
                        }
                    }
                } else if (key.contains("key_mgmt")) {
                    key_mgmt = value;
                } else if (key.contains("psk") && mode == 1) {
                    if (value.startsWith("\"") && value.endsWith("\"")) {
                        psk = value.substring(1, value.length() - 1);
                    }
                }
            }
        }

        if (mode == 1) {
            if (key_mgmt.contains("WPA-PSK")) {
                map.put("ssid", ssid);
                map.put("psk", psk);
                this.list.add(map);
            }
        } else if (mode == 2) {
            if (!key_mgmt.contains("WPA-PSK")) {
                map.put("ssid", ssid);
                map.put("psk", key_mgmt);
                this.list.add(map);
            }
        }
    }

    /* Android 8.0 之后的解析 WiFi 方法 */
    private void addOreo(String block) {
        // 这个方法在 Android 7.0 及以下不会调用，但保留以防万一
        if (mode == 1 && !block.contains("WPA_PSK")) return;
        if (mode == 2 && block.contains("WPA_PSK")) return;

        Matcher ssidM = SSID_PAT.matcher(block);
        Matcher pskM = PSK_PAT.matcher(block);
        Matcher keyM = KEYMGMT_PAT.matcher(block);
        if (!ssidM.find() || !pskM.find() || !keyM.find()) return;

        String ssid = ssidM.group(1);
        String psk = pskM.group(1);
        String keyMgmt = keyM.group(2);

        // 添加空检查
        if (keyMgmt == null) return;

        if (mode == 1 && !keyMgmt.contains("WPA_PSK")) return;
        if (mode == 2 && keyMgmt.contains("WPA_PSK")) return;

        HashMap<String, String> map = new HashMap<>(4);
        map.put("view", block);
        map.put("ssid", ssid);
        map.put("psk", mode == 1 ? psk : keyMgmt.replace("-", ""));
        list.add(map);
    }

    /* 日志记录方法 */
    public void exceptionCatch(String strcontent) {
        String path = "/storage/emulated/0/Android/data/com.zzz.wifiview/debug/debug.txt";
        String strContent = strcontent + "\r\n";

        new Thread(() -> {
            try {
                File file = new File(path);
                File parentFile = file.getParentFile();
                if (parentFile != null && !parentFile.exists()) {
                    boolean dirsCreated = parentFile.mkdirs();
                    if (!dirsCreated) {
                        // 可以选择记录创建目录失败的情况
                        return;
                    }
                }
                if (!file.exists()) {
                    boolean fileCreated = file.createNewFile();
                    if (!fileCreated) {
                        // 可以选择记录创建文件失败的情况
                        return;
                    }
                }

                try (RandomAccessFile rf = new RandomAccessFile(file, "rwd")) {
                    rf.seek(file.length());
                    rf.write(strContent.getBytes());
                }
            } catch (Exception e) {
                // 忽略日志错误
            }
        }).start();
    }

    public ArrayList<Map<String, String>> getList(Context context) {
        ArrayList<Map<String, String>> m = new ArrayList<>();
        for (Map<String, String> map : this.list) {
            if (map.containsKey("ssid") && map.containsKey("psk")) {
                m.add(map);
            }
        }
        return this.sorting(m, context);
    }

    private ArrayList<Map<String, String>> sorting(ArrayList<Map<String, String>> lv, Context context) {
        if (lv.isEmpty()) return lv;
        Collections.sort(lv, new sort(getSSID(context)));
        return lv;
    }

    private String getSSID(Context context) {
        try {
            WifiManager wifiManager = (WifiManager) context.getApplicationContext().getSystemService(Context.WIFI_SERVICE);
            if (!wifiManager.isWifiEnabled()) return "";
            WifiInfo wifiInfo = wifiManager.getConnectionInfo();
            String ssid = wifiInfo.getSSID();

            if (ssid.contains("\"")) {
                ssid = ssid.substring(1, ssid.length() - 1);
            } else {
                ssid = toUTF8(ssid);
            }
            return ssid;
        } catch (Exception e) {
            exceptionCatch("getCurrentWiFi: " + e.getMessage());
            return "";
        }
    }

    private static String toUTF8(String s) {
        if (s == null || s.isEmpty()) return s;

        if ((s.length() & 1) == 0 && s.matches("^[0-9A-Fa-f]+$")) {
            try {
                int len = s.length() / 2;
                byte[] buf = new byte[len];
                for (int i = 0; i < len; i++) {
                    int start = i << 1;
                    buf[i] = (byte) Integer.parseInt(s.substring(start, start + 2), 16);
                }

                return new String(buf, StandardCharsets.UTF_8);
            } catch (Exception ignore) {
            }
        }
        return s;
    }
}

class sort implements Comparator<Map<String, String>> {
    private final String current;

    public sort(String current) {
        this.current = current;
    }

    @Override
    public int compare(Map<String, String> t1, Map<String, String> t2) {
        String s1 = t1.get("ssid");
        String s2 = t2.get("ssid");

        // 添加空检查
        if (s1 == null) s1 = "";
        if (s2 == null) s2 = "";

        if (s1.equals(s2)) return 0;
        if (s1.equals(current)) return -1;
        if (s2.equals(current)) return 1;
        return s1.compareToIgnoreCase(s2);
    }
}