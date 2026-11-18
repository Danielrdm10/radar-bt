package com.daniel.radar;

import android.Manifest;
import android.app.Activity;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothSocket;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.view.WindowManager;
import android.webkit.JavascriptInterface;
import android.webkit.WebSettings;
import android.webkit.WebView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.lang.reflect.Method;
import java.util.UUID;

public class MainActivity extends Activity {

    // Identificador padrão do perfil serial Bluetooth (SPP), usado pelo HC-05
    private static final UUID SPP = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB");

    private WebView web;
    private volatile BluetoothSocket socket;
    private volatile boolean lendo = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // Mantém a tela ligada enquanto o radar estiver aberto
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        web = new WebView(this);
        WebSettings ws = web.getSettings();
        ws.setJavaScriptEnabled(true);
        ws.setDomStorageEnabled(true);
        // Deixa a página chamar as funções da classe Ponte como "AndroidBT"
        web.addJavascriptInterface(new Ponte(), "AndroidBT");
        web.loadUrl("file:///android_asset/radar.html");
        setContentView(web);

        pedirPermissoes();
    }

    // No Android 12 ou mais novo, o app precisa pedir permissão para usar o Bluetooth
    private void pedirPermissoes() {
        if (Build.VERSION.SDK_INT >= 31
                && checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT)
                != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{
                    Manifest.permission.BLUETOOTH_CONNECT,
                    Manifest.permission.BLUETOOTH_SCAN}, 1);
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        // Depois que a permissão é dada, recarrega a lista de aparelhos na página
        web.evaluateJavascript("window.carregarPareados && window.carregarPareados()", null);
    }

    // Chama uma função JavaScript da página passando um texto
    private void chamarJs(final String funcao, final String texto) {
        runOnUiThread(() -> web.evaluateJavascript(
                "window." + funcao + " && window." + funcao + "(" + JSONObject.quote(texto) + ")",
                null));
    }

    private void fecharSocket() {
        lendo = false;
        try {
            if (socket != null) socket.close();
        } catch (IOException ignored) {
        }
        socket = null;
    }

    @Override
    protected void onDestroy() {
        fecharSocket();
        super.onDestroy();
    }

    // Funções que a página consegue chamar
    private class Ponte {

        @JavascriptInterface
        public String pareados() {
            JSONArray lista = new JSONArray();
            try {
                BluetoothAdapter bt = BluetoothAdapter.getDefaultAdapter();
                if (bt == null) return "[]";
                for (BluetoothDevice d : bt.getBondedDevices()) {
                    JSONObject o = new JSONObject();
                    o.put("nome", d.getName() == null ? d.getAddress() : d.getName());
                    o.put("endereco", d.getAddress());
                    lista.put(o);
                }
            } catch (SecurityException e) {
                chamarJs("radarErro", "Permita o acesso ao Bluetooth");
            } catch (Exception ignored) {
            }
            return lista.toString();
        }

        @JavascriptInterface
        public void conectar(final String endereco) {
            fecharSocket();
            final boolean[] jaConectou = {false};
            new Thread(() -> {
                try {
                    BluetoothAdapter bt = BluetoothAdapter.getDefaultAdapter();
                    if (bt == null || !bt.isEnabled()) {
                        chamarJs("radarErro", "Ligue o Bluetooth do celular");
                        return;
                    }
                    try {
                        bt.cancelDiscovery();
                    } catch (SecurityException ignored) {
                    }
                    BluetoothDevice dev = bt.getRemoteDevice(endereco);
                    BluetoothSocket s;
                    try {
                        s = dev.createRfcommSocketToServiceRecord(SPP);
                        s.connect();
                    } catch (IOException primeira) {
                        // Plano B: alguns HC-05 só conectam pelo canal 1 direto
                        Method m = dev.getClass().getMethod("createRfcommSocket", int.class);
                        s = (BluetoothSocket) m.invoke(dev, 1);
                        s.connect();
                    }
                    socket = s;
                    lendo = true;
                    jaConectou[0] = true;
                    chamarJs("radarConectado", "");

                    BufferedReader leitor = new BufferedReader(
                            new InputStreamReader(s.getInputStream(), "UTF-8"));
                    String linha;
                    while (lendo && (linha = leitor.readLine()) != null) {
                        chamarJs("radarLinha", linha);
                    }
                    if (lendo) chamarJs("radarErro", "Conexão perdida");
                } catch (SecurityException e) {
                    chamarJs("radarErro", "Permita o acesso ao Bluetooth");
                } catch (Exception e) {
                    if (!jaConectou[0]) chamarJs("radarErro", "Falha ao conectar (HC-05 ligado?)");
                    else if (lendo) chamarJs("radarErro", "Conexão perdida");
                } finally {
                    fecharSocket();
                }
            }).start();
        }

        @JavascriptInterface
        public void desconectar() {
            fecharSocket();
        }
    }
}
