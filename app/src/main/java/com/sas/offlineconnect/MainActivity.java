package com.sas.offlineconnect;

import android.Manifest;
import android.app.Activity;
import android.os.Build;
import android.os.Bundle;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.view.Gravity;
import android.widget.*;
import androidx.annotation.NonNull;
import com.google.android.gms.nearby.Nearby;
import com.google.android.gms.nearby.connection.*;
import org.json.JSONObject;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class MainActivity extends Activity {
    private static final int REQ = 41;
    private static final String SERVICE = "com.sas.offlineconnect.CHAT";
    private ConnectionsClient client;
    private final Set<String> peers = ConcurrentHashMap.newKeySet();
    private final Set<String> seen = Collections.synchronizedSet(new HashSet<>());
    private LinearLayout chat;
    private TextView status, count;
    private EditText input;
    private final String name = "SAS-" + UUID.randomUUID().toString().substring(0,5);
    private boolean started;

    @Override public void onCreate(Bundle b) {
        super.onCreate(b);
        client = Nearby.getConnectionsClient(this);
        makeUi();
        permissions();
    }
    private void makeUi() {
        LinearLayout root = new LinearLayout(this); root.setOrientation(1); root.setPadding(16,16,16,12); root.setBackgroundColor(0xfff2f6f3);
        TextView title = new TextView(this); title.setText("SAS OFFLINE CONNECT"); title.setTextSize(21); title.setTextColor(0xff167347); title.setGravity(Gravity.CENTER); title.setPadding(4,12,4,12); root.addView(title);
        status = new TextView(this); status.setText("Menyiapkan koneksi…"); root.addView(status);
        count = new TextView(this); count.setText("Perangkat terhubung: 0"); count.setPadding(0,6,0,8); root.addView(count);
        ScrollView scroll = new ScrollView(this); chat = new LinearLayout(this); chat.setOrientation(1); scroll.addView(chat); root.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));
        LinearLayout row = new LinearLayout(this); input = new EditText(this); input.setSingleLine(true); input.setHint("Tulis pesan…"); row.addView(input,new LinearLayout.LayoutParams(0,-2,1));
        Button send = new Button(this); send.setText("Kirim"); send.setOnClickListener(v -> send()); row.addView(send); root.addView(row);
        TextView hint = new TextView(this); hint.setText("Tanpa internet/kuota. Bluetooth dan Wi-Fi perlu aktif."); hint.setTextSize(11); root.addView(hint);
        setContentView(root); add("Sistem","Aplikasi siap mencari HP SAS di sekitar.");
    }
    private void permissions() {
        ArrayList<String> p = new ArrayList<>();
        if (Build.VERSION.SDK_INT >= 31) { p.add(Manifest.permission.BLUETOOTH_SCAN); p.add(Manifest.permission.BLUETOOTH_CONNECT); p.add(Manifest.permission.BLUETOOTH_ADVERTISE); }
        else p.add(Manifest.permission.ACCESS_FINE_LOCATION);
        if (Build.VERSION.SDK_INT >= 33) p.add(Manifest.permission.NEARBY_WIFI_DEVICES);
        ArrayList<String> missing = new ArrayList<>();
        for(String x:p) if(checkSelfPermission(x)!=PackageManager.PERMISSION_GRANTED) missing.add(x);
        if(missing.isEmpty()) start(); else requestPermissions(missing.toArray(new String[0]),REQ);
    }
    @Override public void onRequestPermissionsResult(int r,@NonNull String[] p,@NonNull int[] g) {
        super.onRequestPermissionsResult(r,p,g);
        if(r==REQ) { boolean ok=true; for(int x:g) if(x!=PackageManager.PERMISSION_GRANTED) ok=false; if(ok) start(); else status.setText("Izin perangkat sekitar diperlukan."); }
    }
    private void start() {
        if(started)return; started=true; status.setText("Mencari perangkat otomatis…");
        Strategy s=Strategy.P2P_CLUSTER;
        client.startAdvertising(name,SERVICE,life,new AdvertisingOptions.Builder().setStrategy(s).build())
            .addOnFailureListener(e->runOnUiThread(()->status.setText("Iklan gagal: "+e.getLocalizedMessage())));
        client.startDiscovery(SERVICE,found,new DiscoveryOptions.Builder().setStrategy(s).build())
            .addOnFailureListener(e->runOnUiThread(()->status.setText("Pencarian gagal: "+e.getLocalizedMessage())));
    }
    private final EndpointDiscoveryCallback found=new EndpointDiscoveryCallback(){
        public void onEndpointFound(String id,DiscoveredEndpointInfo info){
            if(info.getEndpointName().startsWith("SAS-")&&!peers.contains(id)) client.requestConnection(name,id,life);
        }
        public void onEndpointLost(String id){}
    };
    private final ConnectionLifecycleCallback life=new ConnectionLifecycleCallback(){
        public void onConnectionInitiated(String id,ConnectionInfo info){
            if(info.getEndpointName().startsWith("SAS-")) client.acceptConnection(id,receiver); else client.rejectConnection(id);
        }
        public void onConnectionResult(String id,ConnectionResolution result){
            if(result.getStatus().isSuccess()){peers.add(id);runOnUiThread(()->{refresh();add("Sistem","Terhubung dengan perangkat SAS.");});}
            else runOnUiThread(()->add("Sistem","Koneksi gagal."));
        }
        public void onDisconnected(String id){peers.remove(id);runOnUiThread(()->refresh());}
    };
    private final PayloadCallback receiver=new PayloadCallback(){
        public void onPayloadReceived(String id,Payload p){
            if(p.getType()!=Payload.Type.BYTES)return;
            try{
                JSONObject o=new JSONObject(new String(p.asBytes(),java.nio.charset.StandardCharsets.UTF_8));
                String mid=o.getString("id"); if(!seen.add(mid))return;
                String body=o.getString("body"),sender=o.optString("sender","Teman"); int hops=o.optInt("hops",0);
                runOnUiThread(()->add(sender,body));
                if(hops<3){o.put("hops",hops+1);byte[] data=o.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8);for(String peer:peers)if(!peer.equals(id))client.sendPayload(peer,Payload.fromBytes(data));}
            }catch(Exception ignored){}
        }
        public void onPayloadTransferUpdate(String id,PayloadTransferUpdate u){}
    };
    private void send(){
        String body=input.getText().toString().trim(); if(body.isEmpty())return;
        if(peers.isEmpty()){add("Sistem","Belum ada perangkat terhubung. Buka aplikasi di HP lain dan dekatkan.");return;}
        try{String id=UUID.randomUUID().toString();seen.add(id);JSONObject o=new JSONObject();o.put("id",id);o.put("sender",name);o.put("body",body);o.put("hops",0);byte[] d=o.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8);for(String peer:peers)client.sendPayload(peer,Payload.fromBytes(d));add("Saya",body);input.setText("");}
        catch(Exception e){add("Sistem","Pesan tidak dapat dikirim.");}
    }
    private void add(String who,String body){TextView t=new TextView(this);t.setText(who+"\n"+body);t.setTextSize(15);t.setTextColor(0xff26352c);t.setPadding(12,10,12,10);t.setBackgroundColor(who.equals("Saya")?0xffd8f1df:Color.WHITE);LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-1,-2);lp.setMargins(2,5,2,5);chat.addView(t,lp);}
    private void refresh(){count.setText("Perangkat terhubung: "+peers.size());}
    @Override protected void onDestroy(){if(client!=null){client.stopAdvertising();client.stopDiscovery();}super.onDestroy();}
}
