package com.zzz.wifiview;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Bundle;
import android.text.InputType;
import android.util.Log;
import android.view.Menu;
import android.view.MenuItem;
import android.widget.ArrayAdapter;
import android.widget.EditText;
import android.widget.ListAdapter;
import android.widget.ListView;
import android.widget.PopupMenu;
import android.widget.Toast;

import androidx.documentfile.provider.DocumentFile;

import java.io.DataOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

public class FileActivity extends Activity {

    private static final String TAG = "FileActivity";
    private static final String PREFS_NAME = "WifiViewPrefs";
    private static final String KEY_BACKUP_URI = "backup_uri";
    private static final int REQUEST_CODE_BACKUP_DIR = 1001;
    private static final int REQUEST_CODE_SET_BACKUP_DIR = 1002;

    List<String> backupFileNames; // 改为存储文件名列表
    List<String> backupFileUris; // 存储文件URI列表
    PopupMenu popup;

    Uri backupDirUri; // 备份目录的SAF URI
    String wifiPath; // /data/misc/wifi/xxx.xml

    int num; // 计数菌
    private String pendingBackupName; // 临时保存备份名称

    /* 设定时间格式 */
    SimpleDateFormat formatter = new SimpleDateFormat("yyyyMMddHHmm", Locale.getDefault());

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.list);

        /* 设置 WiFi 密码文件路径 */
        if (android.os.Build.VERSION.SDK_INT >= 26) {
            if (android.os.Build.VERSION.SDK_INT >= 30) {
                wifiPath = "/data/misc/apexdata/com.android.wifi/WifiConfigStore.xml";
            } else {
                wifiPath = "/data/misc/wifi/WifiConfigStore.xml";
            }
        } else {
            wifiPath = "/data/misc/wifi/wpa_supplicant.conf";
        }

        /* 获取备份文件列表 */
        refreshFileList();
    }

    @Override
    protected void onResume() {
        super.onResume();
        // 每次返回时刷新列表
        refreshFileList();
    }

    private void refreshFileList() {
        backupFileNames = new ArrayList<>();
        backupFileUris = new ArrayList<>();
        backupDirUri = getBackupDirUri();

        if (backupDirUri != null) {
            // 如果有保存的备份目录，列出文件
            Log.d(TAG, "尝试列出备份文件，URI: " + backupDirUri);
            listBackupFiles();
        } else {
            Log.d(TAG, "没有备份目录URI");
        }

        // 按文件名排序（时间顺序）
        if (!backupFileNames.isEmpty()) {
            // 同时排序文件名和URI
            List<FileInfo> fileInfos = new ArrayList<>();
            for (int i = 0; i < backupFileNames.size(); i++) {
                fileInfos.add(new FileInfo(backupFileNames.get(i), backupFileUris.get(i)));
            }

            Collections.sort(fileInfos, (f1, f2) -> {
                return f2.fileName.compareTo(f1.fileName); // 降序排列，最新的在前面
            });

            backupFileNames.clear();
            backupFileUris.clear();
            for (FileInfo info : fileInfos) {
                backupFileNames.add(info.fileName);
                backupFileUris.add(info.fileUri);
            }
        }

        num = backupFileNames.size(); // 备份条数
        Log.d(TAG, "找到 " + num + " 个备份文件");

        // 更新UI
        updateListView();

        // 更新菜单标题
        invalidateOptionsMenu();
    }

    private void updateListView() {
        runOnUiThread(() -> {
            final ListView lv = findViewById(R.id.lv);
            if (backupFileNames.isEmpty()) {
                List<String> emptyList = new ArrayList<>();
                emptyList.add("暂无备份文件");
                ListAdapter adapter = new ArrayAdapter<>(this, android.R.layout.simple_list_item_1, emptyList);
                lv.setAdapter(adapter);
                lv.setOnItemClickListener(null);
            } else {
                ListAdapter adapter = new ArrayAdapter<>(this, android.R.layout.simple_list_item_1, backupFileNames);
                lv.setAdapter(adapter);
                lv.setOnItemClickListener((parent, view, position, id) -> {
                    popup = new PopupMenu(FileActivity.this, view);
                    getMenuInflater().inflate(R.menu.file, popup.getMenu());
                    popup.setOnMenuItemClickListener(item -> {
                        int itemId = item.getItemId();
                        String fileName = backupFileNames.get(position);
                        String fileUri = backupFileUris.get(position);
                        if (itemId == R.id.menu_recovery) { // 时光穿梭
                            showRestoreDialog(fileName);
                        } else if (itemId == R.id.menu_read) { // 读取备份
                            if (fileUri != null) {
                                Intent intent1 = new Intent();
                                intent1.setClass(FileActivity.this, ViewActivity.class);
                                intent1.putExtra("name", fileName);
                                intent1.putExtra("uri", fileUri);
                                intent1.putExtra("read", true);
                                startActivity(intent1);
                            } else {
                                Toast.makeText(FileActivity.this, "无法访问文件", Toast.LENGTH_SHORT).show();
                            }
                        } else if (itemId == R.id.menu_delete1) { // 删除备份
                            deleteBackupFile(fileName, fileUri);
                        } else {
                            return false;
                        }
                        return true;
                    });
                    popup.show();
                });
            }
        });
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        super.onCreateOptionsMenu(menu);
        menu.clear(); // 清空菜单重新创建
        menu.add(0, 0, 0, "添加备份");
        menu.add(0, 1, 0, "Notice");
        menu.add(0, 2, 0, "设置备份位置");
        menu.getItem(1).setEnabled(false);
        menu.getItem(1).setTitle("共 " + num + " 条备份");
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        if (item.getItemId() == 0) { // 添加备份
            showBackupDialog();
            return true;
        } else if (item.getItemId() == 2) { // 设置备份位置
            setBackupDirectory();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    private void showBackupDialog() {
        final EditText et = new EditText(this);
        AlertDialog.Builder BackupDialog = new AlertDialog.Builder(this);
        BackupDialog
                .setTitle("备份名称")
                .setView(et);

        et.setText(formatter.format(System.currentTimeMillis()));
        et.setInputType(InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD);

        BackupDialog
                .setPositiveButton("开始备份", (dialog, which) -> {
                    pendingBackupName = et.getText().toString().trim();

                    if (pendingBackupName.isEmpty()) {
                        Toast.makeText(this, "请输入备份名称", Toast.LENGTH_SHORT).show();
                        return;
                    }

                    if (getBackupDirUri() != null) {
                        // 已有备份目录，直接备份
                        performBackup(pendingBackupName);
                    } else {
                        // 没有备份目录，让用户选择
                        chooseBackupDirectory();
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void showRestoreDialog(final String fileName) {
        AlertDialog.Builder RestoreDialog = new AlertDialog.Builder(this);
        RestoreDialog
                .setTitle("警告")
                .setMessage("此操作将会替换设备的 WiFi 密码文件。是否继续？\n\n文件：\n" + fileName)
                .setPositiveButton("继续", (dialog, which) -> restoreBackup(fileName))
                .setNegativeButton("取消", null)
                .show();
    }

    private void chooseBackupDirectory() {
        @SuppressLint("InlinedApi")
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION |
                Intent.FLAG_GRANT_WRITE_URI_PERMISSION |
                Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        startActivityForResult(intent, REQUEST_CODE_BACKUP_DIR);
    }

    private void setBackupDirectory() {
        @SuppressLint("InlinedApi")
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION |
                Intent.FLAG_GRANT_WRITE_URI_PERMISSION |
                Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        startActivityForResult(intent, REQUEST_CODE_SET_BACKUP_DIR);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);

        if (resultCode == RESULT_OK && data != null) {
            Uri treeUri = data.getData();

            if (treeUri == null) {
                Toast.makeText(this, "无效的目录", Toast.LENGTH_SHORT).show();
                return;
            }

            Log.d(TAG, "用户选择的目录URI: " + treeUri);

            // 获取永久访问权限
            try {
                int takeFlags = Intent.FLAG_GRANT_READ_URI_PERMISSION |
                        Intent.FLAG_GRANT_WRITE_URI_PERMISSION;

                // 尝试获取持久化权限
                getContentResolver().takePersistableUriPermission(treeUri, takeFlags);
                Log.d(TAG, "成功获取持久化权限");

            } catch (SecurityException e) {
                Log.e(TAG, "无法获取持久化权限", e);
                Toast.makeText(this, "注意：可能无法持久保存目录权限", Toast.LENGTH_SHORT).show();
            } catch (Exception e) {
                Log.e(TAG, "权限处理异常", e);
            }

            if (requestCode == REQUEST_CODE_BACKUP_DIR) {
                // 备份时选择的目录
                // 询问是否设为默认位置
                askSetAsDefaultBackupDir(treeUri);
            } else if (requestCode == REQUEST_CODE_SET_BACKUP_DIR) {
                // 手动设置备份位置
                saveBackupDirUri(treeUri);
                Toast.makeText(this, "备份位置已设置", Toast.LENGTH_SHORT).show();
                refreshFileList();
            }
        } else if (requestCode == REQUEST_CODE_BACKUP_DIR) {
            Toast.makeText(this, "未选择备份目录", Toast.LENGTH_SHORT).show();
            pendingBackupName = null; // 重置待备份名称
        }
    }

    private void askSetAsDefaultBackupDir(final Uri uri) {
        new AlertDialog.Builder(this)
                .setTitle("提示")
                .setMessage("将选择的目录设为默认备份位置？\n\n选择\"确定\"后，以后备份将直接使用此目录。")
                .setPositiveButton("确定", (dialog, which) -> {
                    saveBackupDirUri(uri);
                    if (pendingBackupName != null) {
                        performBackup(pendingBackupName);
                    }
                })
                .setNegativeButton("否", (dialog, which) -> {
                    // 临时使用，不保存
                    backupDirUri = uri;
                    if (pendingBackupName != null) {
                        performBackup(pendingBackupName);
                    }
                })
                .setNeutralButton("取消", (dialog, which) -> {
                    pendingBackupName = null; // 取消备份
                })
                .show();
    }

    private void performBackup(String backupName) {
        // 创建 final 副本供 lambda 使用
        final String finalBackupName = backupName;

        new Thread(() -> {
            Process   process = null;
            DataOutputStream os = null;
            InputStream shellIn = null;
            android.content.ContentResolver resolver = getContentResolver();

            try {
                // 1. 在 SAF 备份目录里创建空文件
                Uri backupFileUri = createBackupFile(finalBackupName);
                if (backupFileUri == null) {
                    runOnUiThread(() ->
                            Toast.makeText(FileActivity.this, "创建备份文件失败", Toast.LENGTH_SHORT).show());
                    return;
                }

                // 2. 打开 SAF 输出流（wt = truncate write）
                try (OutputStream safOut = resolver.openOutputStream(backupFileUri, "wt")) {
                    if (safOut == null) {
                        runOnUiThread(() ->
                                Toast.makeText(FileActivity.this, "无法打开备份文件输出流", Toast.LENGTH_SHORT).show());
                        return;
                    }

                    // 3. 起 root 进程，cat 系统 Wi-Fi 文件
                    process = Runtime.getRuntime().exec("su");
                    os = new DataOutputStream(process.getOutputStream());

                    // 根据系统版本生成正确路径
                    String wifiPath;
                    if (android.os.Build.VERSION.SDK_INT >= 30) {
                        wifiPath = "/data/misc/apexdata/com.android.wifi/WifiConfigStore.xml";
                    } else if (android.os.Build.VERSION.SDK_INT >= 26) {
                        wifiPath = "/data/misc/wifi/WifiConfigStore.xml";
                    } else {
                        wifiPath = "/data/misc/wifi/wpa_supplicant.conf";
                    }

                    // 写 shell 命令
                    os.writeBytes("cat \"" + wifiPath + "\"\n");
                    os.writeBytes("exit\n");
                    os.flush();

                    // 4. 直接读 su 进程的标准输出，边读边写 SAF
                    shellIn = process.getInputStream();
                    byte[] buf = new byte[8192];
                    int len;
                    long total = 0;
                    while ((len = shellIn.read(buf)) != -1) {
                        safOut.write(buf, 0, len);
                        total += len;
                    }

                    // 5. 等待 su 结束并检查退出码
                    int exitCode = process.waitFor();
                    if (exitCode != 0 || total == 0) {
                        // 删除刚才创建的空白备份文件
                        try {
                            resolver.delete(backupFileUri, null, null);
                        } catch (Exception ignored) {}
                        runOnUiThread(() ->
                                Toast.makeText(FileActivity.this, "读取 WiFi 文件失败（空或权限不足）", Toast.LENGTH_LONG).show());
                        return;
                    }

                    Log.d(TAG, "备份成功，写入 " + total + " 字节");
                    runOnUiThread(() -> {
                        Toast.makeText(FileActivity.this, "备份完成: " + finalBackupName, Toast.LENGTH_SHORT).show();
                        // 延迟刷新列表
                        new android.os.Handler().postDelayed(FileActivity.this::refreshFileList, 500);
                    });

                } catch (Exception e) {
                    Log.e(TAG, "备份写入异常", e);
                    runOnUiThread(() ->
                            Toast.makeText(FileActivity.this, "备份写入失败: " + e.getMessage(), Toast.LENGTH_LONG).show());
                }

            } catch (Exception e) {
                Log.e(TAG, "Backup failed", e);
                runOnUiThread(() ->
                        Toast.makeText(FileActivity.this, "备份失败: " + e.getMessage(), Toast.LENGTH_LONG).show());
            } finally {
                // 6. 清理资源
                try {
                    if (shellIn != null) shellIn.close();
                } catch (Exception ignored) {}
                try {
                    if (os != null) os.close();
                } catch (Exception ignored) {}
                if (process != null) process.destroy();
                runOnUiThread(() -> pendingBackupName = null);
            }
        }).start();
    }
    private void restoreBackup(String fileName) {
        // 创建final副本以便在lambda表达式中使用
        final String finalFileName = fileName;

        new Thread(() -> {
            try {
                Uri backupFileUri = getBackupFileUriFromName(finalFileName);
                if (backupFileUri == null) {
                    runOnUiThread(() -> Toast.makeText(FileActivity.this, "备份文件不存在", Toast.LENGTH_SHORT).show());
                    return;
                }

                // 先复制到临时文件
                File tempFile = new File(getCacheDir(), "temp_restore_" + System.currentTimeMillis());
                try (InputStream in = getContentResolver().openInputStream(backupFileUri)) {
                    if (in == null) {
                        runOnUiThread(() -> Toast.makeText(FileActivity.this, "无法读取备份文件", Toast.LENGTH_SHORT).show());
                        return;
                    }

                    try (OutputStream out = new FileOutputStream(tempFile)) {
                        byte[] buffer = new byte[8192];
                        int length;
                        while ((length = in.read(buffer)) > 0) {
                            out.write(buffer, 0, length);
                        }
                    }
                }

                if (!tempFile.exists() || tempFile.length() == 0) {
                    runOnUiThread(() -> Toast.makeText(FileActivity.this, "备份文件无效或为空", Toast.LENGTH_SHORT).show());
                    return;
                }

                // 然后复制到系统位置（需要root权限）
                cmd("cp -f " + tempFile.getAbsolutePath() + " " + wifiPath);
                cmd("chmod 660 " + wifiPath);

                // 删除临时文件
                tempFile.delete();

                runOnUiThread(() -> {
                    Toast.makeText(FileActivity.this, "恢复完成，重启后生效", Toast.LENGTH_SHORT).show();

                    // 询问是否重启
                    new AlertDialog.Builder(FileActivity.this)
                            .setTitle("提示")
                            .setMessage("WiFi配置文件已恢复，需要重启系统才能使更改生效。是否立即重启？")
                            .setPositiveButton("重启", (dialog, which) -> cmd("reboot"))
                            .setNegativeButton("稍后", null)
                            .show();
                });

            } catch (Exception e) {
                Log.e(TAG, "Restore failed", e);
                runOnUiThread(() -> Toast.makeText(FileActivity.this, "恢复失败: " + e.getMessage(), Toast.LENGTH_LONG).show());
            }
        }).start();
    }

    private void deleteBackupFile(String fileName, String fileUri) {
        try {
            Log.d(TAG, "尝试删除文件: " + fileName + ", URI: " + fileUri);

            boolean deleted = false;
            Uri backupFileUri = Uri.parse(fileUri);

            // 尝试通过URI删除
            if (backupFileUri != null) {
                try {
                    deleted = getContentResolver().delete(backupFileUri, null, null) > 0;
                    if (deleted) {
                        Log.d(TAG, "通过URI删除成功");
                    }
                } catch (Exception e) {
                    Log.e(TAG, "Delete by URI failed", e);
                }
            }

            // 如果URI删除失败，尝试通过DocumentFile删除
            if (!deleted && backupDirUri != null) {
                DocumentFile dir = DocumentFile.fromTreeUri(this, backupDirUri);
                if (dir != null && dir.exists()) {
                    DocumentFile file = dir.findFile(fileName);
                    if (file != null && file.exists()) {
                        deleted = file.delete();
                        if (deleted) {
                            Log.d(TAG, "通过DocumentFile删除成功");
                        }
                    } else {
                        Log.w(TAG, "文件不存在: " + fileName);
                    }
                } else {
                    Log.w(TAG, "目录不存在或无法访问");
                }
            }

            if (deleted) {
                Toast.makeText(this, "已删除: " + fileName, Toast.LENGTH_SHORT).show();
                // 延迟刷新列表
                new android.os.Handler().postDelayed(this::refreshFileList, 300);
            } else {
                Toast.makeText(this, "删除失败，文件可能不存在", Toast.LENGTH_SHORT).show();
            }
        } catch (Exception e) {
            Log.e(TAG, "Delete failed", e);
            Toast.makeText(this, "删除失败: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    /* 列出备份文件 */
    private void listBackupFiles() {
        try {
            if (backupDirUri == null) {
                Log.w(TAG, "备份目录URI为空");
                return;
            }

            Log.d(TAG, "开始列出文件，URI: " + backupDirUri);

            DocumentFile dir = DocumentFile.fromTreeUri(this, backupDirUri);
            if (dir == null) {
                Log.w(TAG, "无法从URI创建DocumentFile");
                return;
            }

            if (!dir.exists()) {
                Log.w(TAG, "目录不存在");
                return;
            }

            Log.d(TAG, "目录存在，开始列出文件...");

            DocumentFile[] files = dir.listFiles();

            Log.d(TAG, "找到 " + files.length + " 个文件/目录");

            for (DocumentFile file : files) {
                if (!file.isDirectory()) {
                    String fileName = file.getName();
                    String fileUri = file.getUri().toString();
                    if (fileName != null) {
                        backupFileNames.add(fileName);
                        backupFileUris.add(fileUri);
                        Log.d(TAG, "添加备份文件: " + fileName);
                    } else {
                        Log.w(TAG, "文件名为null: " + fileUri);
                    }
                } else {
                    Log.d(TAG, "跳过目录: " + file.getName());
                }
            }

        } catch (Exception e) {
            Log.e(TAG, "List backup files failed", e);
            runOnUiThread(() -> Toast.makeText(this, "无法列出备份文件: " + e.getMessage(), Toast.LENGTH_LONG).show());
        }
    }

    /* 执行命令 */
    public void cmd(String command) {
        Process process = null;
        DataOutputStream os = null;
        try {
            process = Runtime.getRuntime().exec("su");
            os = new DataOutputStream(process.getOutputStream());
            os.writeBytes(command + "\n");
            os.writeBytes("exit\n");
            os.flush();
            int exitCode = process.waitFor();
            Log.d(TAG, "命令执行完成: " + command + ", 退出码: " + exitCode);
        } catch (Exception e) {
            Log.e(TAG, "Command execution failed: " + command, e);
        } finally {
            try {
                if (os != null) {
                    os.close();
                }
                if (process != null) {
                    process.destroy();
                }
            } catch (Exception e) {
                Log.e(TAG, "Error closing resources", e);
            }
        }
    }

    /* SAF相关辅助方法 */

    private void saveBackupDirUri(Uri uri) {
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        String uriString = uri.toString();
        prefs.edit().putString(KEY_BACKUP_URI, uriString).apply();
        backupDirUri = uri;
        Log.d(TAG, "保存备份目录URI: " + uriString);
    }

    private Uri getBackupDirUri() {
        if (backupDirUri != null) {
            return backupDirUri;
        }
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        String uriString = prefs.getString(KEY_BACKUP_URI, null);
        if (uriString != null) {
            backupDirUri = Uri.parse(uriString);
            Log.d(TAG, "从SharedPreferences读取备份目录URI: " + uriString);
        } else {
            Log.d(TAG, "SharedPreferences中没有备份目录URI");
        }
        return backupDirUri;
    }

    private Uri createBackupFile(String fileName) {
        try {
            Uri dirUri = getBackupDirUri();
            if (dirUri == null) {
                Log.e(TAG, "目录URI为空，无法创建文件");
                return null;
            }

            DocumentFile dir = DocumentFile.fromTreeUri(this, dirUri);
            if (dir == null) {
                Log.e(TAG, "无法创建DocumentFile目录");
                return null;
            }

            if (!dir.exists()) {
                Log.e(TAG, "目录不存在");
                return null;
            }

            Log.d(TAG, "创建备份文件: " + fileName);

            // 检查文件是否已存在，如果存在则删除
            DocumentFile existingFile = dir.findFile(fileName);
            if (existingFile != null) {
                Log.d(TAG, "文件已存在，尝试删除");
                boolean deleted = existingFile.delete();
                Log.d(TAG, "删除结果: " + deleted);
            }

            // 创建新文件
            DocumentFile newFile = dir.createFile("application/octet-stream", fileName);
            if (newFile != null) {
                Log.d(TAG, "文件创建成功: " + newFile.getUri());
                return newFile.getUri();
            } else {
                Log.e(TAG, "文件创建失败");
                return null;
            }

        } catch (Exception e) {
            Log.e(TAG, "Create backup file failed", e);
            return null;
        }
    }

    private Uri getBackupFileUriFromName(String fileName) {
        try {
            Uri dirUri = getBackupDirUri();
            if (dirUri == null) return null;

            DocumentFile dir = DocumentFile.fromTreeUri(this, dirUri);
            if (dir == null) return null;

            DocumentFile file = dir.findFile(fileName);
            if (file != null && file.exists()) {
                return file.getUri();
            }
            return null;

        } catch (Exception e) {
            Log.e(TAG, "Get backup file failed", e);
            return null;
        }
    }

    // 内部类用于同时存储文件名和URI
    private static class FileInfo {
        String fileName;
        String fileUri;

        FileInfo(String fileName, String fileUri) {
            this.fileName = fileName;
            this.fileUri = fileUri;
        }
    }
}