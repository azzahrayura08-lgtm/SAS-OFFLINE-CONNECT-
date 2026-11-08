package com.sas.offlineconnect;

import android.Manifest;
import android.app.Activity;
import android.os.Build;
import android.os.Bundle;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.view.Gravity;
import android.view.View;
import android.widget.*;
import androidx.annotation.NonNull;
import com.google.android.gms.nearby.Nearby;
import com.google.android.gms.nearby.connection.*;
import com.google.android.gms.tasks.Task;
import org.json.JSONObject;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class MainActivity extends Activity {
    private static final String SERVICE_ID = "com.sas.offlineconnect.MESSENGER";
    private static final int REQ = 501;
    private final ConnectionsClient client = null;
    private ConnectionsClient connections;
    private final Set<String> endpoints = ConcurrentHashMap.newKeySet();
    private final Set<String> seen = Collections.synchronizedSet(new LinkedHashSet<>());
    private LinearLayout messages;
    private TextView status, peers;
    private EditText composer;
    private boolean started = false;
    private final String deviceName = "SAS-" + UUID.randomUUID().toString().substring(0,5);

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        connections = Nearby.getConnectionsClient(this);
        buildScreen();
        ensurePermissions();
    }

    private void buildScreen() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(18,18,18,12);
        root.setBackgroundColor(Color.rgb(245,248,246));
        TextView title = new TextView(this);
        title.setText("SAS OFFLINE CONNECT");
        title.setTextSize(21); title.setTextColor(Color.rgb(20,105,65)); title.setGravity(Gravity.CENTER);
        title.setPadding(4,12,4,8);
        root.addView(title);
        status = new TextView(this); status.setText("Menyiapkan koneksi…"); status.setTextSize(14);
        status.setPadding(8,8,8,8); root.addView(status);
        peers = new TextView(this); peers.setText("Perangkat terhubung: 0"); peers.setPadding(8,4,8,8); root.addView(peers);
        ScrollView scroll = new ScrollView(this);
        messages = new LinearLayout(this); messages.setOrientation(LinearLayout.VERTICAL); messages.setPadding(6,8,6,8);
        scroll.addView(messages);
        root.addView(scroll, new LinearLayout.LayoutParams(-1,0,1));
        LinearLayout bar = new LinearLayout(this); bar.setOrientation(LinearLayout.HORIZONTAL);
        composer = new EditText(this); composer.setSingleLine(true); composer.setHint("Tulis pesan…");
        bar.addView(composer,new LinearLayout.LayoutParams(0,-2,1));
        Button send = new Button(this); send.setText("Kirim"); send.setOnClickListener(v -> sendMessage());
        bar.addView(send); root.addView(bar);
        TextView note = new TextView(this);
        note.setText("Bluetooth & Wi-Fi harus aktif. Internet/kuota tidak diperlukan.");
        note.setTextSize(11); note.setTextColor(Color.DKGRAY); note.setPadding(4,8,4,2); root.addView(note);
        setContentView(root);
        addMessage("Sistem", "Aplikasi siap mencari perangkat SAS di sekitar.");
    }

    private void ensurePermissions() {
        List<String> p = new ArrayList<>();
        if (Build.VERSION.SDK_INT >= 31) {
            p.add(Manifest.permission.BLUETOOTH_SCAN);
            p.add(Manifest.permission.BLUETOOTH_CONNECT);
            p.add(Manifest.permission.BLUETOOTH_ADVERTISE);
        } else {
            p.add(Manifest.permission.ACCESS_FINE_LOCATION);
        }
        if (Build.VERSION.SDK_INT >= 33) p.add(Manifest.permission.NEARBY_WIFI_DEVICES);
        List<String> missing = new ArrayList<>();
        for (String s : p) if (checkSelfPermission(s) != PackageManager.PERMISSION_GRANTED) missing.add(s);
        if (missing.isEmpty()) startNearby();
        else requestPermissions(missing.toArray(new String[0]), REQ);
    }

    @Override public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] results) {
        super.onRequestPermissionsResult(requestCode, permissions, results);
        if (requestCode == REQ) {
            boolean ok = true; for (int r : results) if (r != PackageManager.PERMISSION_GRANTED) ok = false;
            if (ok) startNearby(); else status.setText("Izin Bluetooth/perangkat sekitar diperlukan untuk mencari HP.");
        }
    }

    private void startNearby() {
        if (started) return;
        started = true;
        status.setText("Mencari perangkat SAS secara otomatis…");
        Strategy strategy = Strategy.P2P_CLUSTER;
        AdvertisingOptions ad = new AdvertisingOptions.Builder().setStrategy(strategy).build();
        DiscoveryOptions dis = new DiscoveryOptions.Builder().setStrategy(strategy).build();

        connections.startAdvertising(deviceName, SERVICE_ID, lifecycle, ad)
            .addOnFailureListener(e -> runOnUiThread(() -> status.setText("Iklan perangkat gagal: " + e.getLocalizedMessage())));
        connections.startDiscovery(SERVICE_ID, discovery, dis)
            .addOnFailureListener(e -> runOnUiThread(() -> status.setText("Pencarian gagal: " + e.getLocalizedMessage())));
    }

    private final EndpointDiscoveryCallback discovery = new EndpointDiscoveryCallback() {
        @Override public void onEndpointFound(String id, DiscoveredEndpointInfo info) {
            if (!endpoints.contains(id) && info.getEndpointName().startsWith("SAS-")) {
                connections.requestConnection(deviceName, id, lifecycle)
                    .addOnFailureListener(e -> runOnUiThread(() -> addMessage("Sistem","Gagal meminta koneksi: "+e.getLocalizedMessage())));
            }
        }
        @Override public void onEndpointLost(String id) {}
    };

    private final ConnectionLifecycleCallback lifecycle = new ConnectionLifecycleCallback() {
        @Override public void onConnectionInitiated(String id, ConnectionInfo info) {
            if (info.getEndpointName().startsWith("SAS-")) connections.acceptConnection(id, payloadCallback);
            else connections.rejectConnection(id);
        }
        @Override public void onConnectionResult(String id, ConnectionResolution result) {
            if (result.getStatus().isSuccess()) {
                endpoints.add(id);
                runOnUiThread(() -> { updatePeers(); addMessage("Sistem","Terhubung dengan perangkat SAS."); });
            } else runOnUiThread(() -> addMessage("Sistem","Koneksi tidak berhasil."));
        }
        @Override public void onDisconnected(String id) {
            endpoints.remove(id);
            runOnUiThread(() -> { updatePeers(); addMessage("Sistem","Perangkat terputus."); });
        }
    };

    private final PayloadCallback payloadCallback = new PayloadCallback() {
        @Override public void onPayloadReceived(String id, Payload payload) {
            if (payload.getType() != Payload.Type.BYTES) return;
            byte[] bytes = payload.asBytes(); if (bytes == null) return;
            try {
                JSONObject obj = new JSONObject(new String(bytes, java.nio.charset.StandardCharsets.UTF_8));
                String mid = obj.getString("id"), body = obj.getString("body"), sender = obj.optString("sender","Teman");
                int hops = obj.optInt("hops",0);
                if (!seen.add(mid)) return;
                runOnUiThread(() -> addMessage(sender, body));
                if (hops < 4) {
                    obj.put("hops", hops + 1);
                    byte[] forwarded = obj.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8);
                    for (String peer : endpoints) if (!peer.equals(id)) connections.sendPayload(peer, Payload.fromBytes(forwarded));
                }
            } catch (Exception ignored) {}
        }
        @Override public void onPayloadTransferUpdate(String id, PayloadTransferUpdate update) {}
    };

    private void sendMessage() {
        String body = composer.getText().toString().trim();
        if (body.isEmpty()) return;
        if (endpoints.isEmpty()) { addMessage("Sistem","Belum ada perangkat terhubung. Dekatkan HP lain dan buka aplikasinya."); return; }
        try {
            String mid = UUID.randomUUID().toString(); seen.add(mid);
            JSONObject obj = new JSONObject(); obj.put("id",mid); obj.put("sender",deviceName); obj.put("body",body); obj.put("hops",0);
            byte[] data = obj.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8);
            for (String peer : endpoints) connections.sendPayload(peer,Payload.fromBytes(data));
            addMessage("Saya",body); composer.setText("");
        } catch (Exception e) { addMessage("Sistem","Pesan gagal disiapkan."); }
    }

    private void addMessage(String who, String body) {
        TextView t = new TextView(this); t.setText(who + "\n" + body); t.setTextSize(15);
        t.setTextColor(Color.rgb(30,40,35)); t.setPadding(14,10,14,10);
        t.setBackgroundColor(who.equals("Saya") ? Color.rgb(211,241,220) : Color.WHITE);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1,-2); lp.setMargins(2,5,2,5);
        messages.addView(t,lp);
    }
    private void updatePeers() { peers.setText("Perangkat terhubung: " + endpoints.size()); }
    @Override protected void onDestroy() {
        if (connections != null) { connections.stopAdvertising(); connections.stopDiscovery(); }
        super.onDestroy();
    }
}
