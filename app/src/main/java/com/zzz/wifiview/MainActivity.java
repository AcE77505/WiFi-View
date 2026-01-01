package com.zzz.wifiview;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.util.Log;
import java.io.DataOutputStream;

public class MainActivity extends Activity {

    private static final String TAG = "MainActivity"; // 添加日志标签

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setTheme(android.R.style.Theme_DeviceDefault); // 暗色主题

        // 检测是否获取root，有则启动ViewActivity
        if (isRoot()) {
            startActivity(new Intent().setClassName("com.zzz.wifiview", "com.zzz.wifiview.ViewActivity"));
            finish();
        } else {
            showNoROOTDialog();
        }
    }


    private void showNoROOTDialog() {
        AlertDialog.Builder noROOTDialog = new AlertDialog.Builder(this);
        noROOTDialog.setTitle("无法获取 ROOT 权限");
        noROOTDialog.setCancelable(false);
        noROOTDialog.setMessage("需要 ROOT 权限以访问 WiFi 密码数据。");
        noROOTDialog.setPositiveButton("关闭", (dialog, which) -> finish());
        noROOTDialog.setNeutralButton("卸载", (dialog, which) -> {
            Uri uri = Uri.parse("package:com.zzz.wifiview");
            Intent intent = new Intent(Intent.ACTION_DELETE, uri);
            startActivity(intent);
            finish();
        });
        noROOTDialog.show();
    }


    /** 判断是否被授予root权限 */
    public static synchronized boolean isRoot() {
        Process process = null;
        DataOutputStream os = null;
        try {
            process = Runtime.getRuntime().exec("su");
            os = new DataOutputStream(process.getOutputStream());
            os.writeBytes("exit\n");
            os.flush();
            int exitValue = process.waitFor();
            return exitValue == 0;
        } catch (Exception e) {
            Log.e(TAG, "Root检测失败", e);
            return false;
        } finally {
            try {
                if (os != null) {
                    os.close();
                }
            } catch (Exception e) {
                Log.e(TAG, "关闭DataOutputStream失败", e);
            }

            try {
                if (process != null) {
                    process.destroy();
                }
            } catch (Exception e) {
                Log.e(TAG, "销毁进程失败", e);
            }
        }
    }


}