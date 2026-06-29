import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

public class GeneradorCarga {
    private static String token = "";
    private static double saldoInicial = 0;
    private static AtomicInteger lecturaExitosa = new AtomicInteger(0);
    private static AtomicInteger transferenciasExitosa = new AtomicInteger(0);
    private static AtomicInteger errorLectura = new AtomicInteger(0);
    private static AtomicInteger errorTransferencia = new AtomicInteger(0);
    private static String urlBase = "http://136.112.140.202:7000";
    private static List<Integer> idsValidos = new ArrayList<>();
    private static Random random = new Random();

    public static void main(String[] args) throws Exception {
        String archivoCsv = args.length > 0 ? args[0] : "generador/alumnos.csv";

        System.out.println("=== Generador de Carga ===");
        System.out.println("Cargando cuentas...");
        cargarCuentas(archivoCsv);

        System.out.println("Registrando usuario...");
        registrarUsuario();

        System.out.println("Login...");
        login();

        System.out.println("Obteniendo saldo inicial...");
        obtenerSaldoInicial();

        System.out.println("\nIniciando prueba de 60 segundos...");
        ejecutarPrueba();

        System.out.println("\n=== Resultados ===");
        System.out.println("Lecturas exitosas: " + lecturaExitosa.get());
        System.out.println("Transferencias exitosas: " + transferenciasExitosa.get());
        System.out.println("Errores en lecturas: " + errorLectura.get());
        System.out.println("Errores en transferencias: " + errorTransferencia.get());

        System.out.println("\nVerificando consistencia...");
        verificarConsistencia();
    }

    private static void cargarCuentas(String archivo) {
        try (BufferedReader br = new BufferedReader(new FileReader(archivo))) {
            String linea;
            while ((linea = br.readLine()) != null) {
                String[] partes = linea.split(",");
                if (partes.length >= 1) {
                    int id = Integer.parseInt(partes[0]);
                    idsValidos.add(id);
                }
            }
            System.out.println("Cargados " + idsValidos.size() + " IDs de cuentas.");
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private static void registrarUsuario() throws Exception {
        String usuario = "testuser";
        String password = "testpass123";

        String json = "{\"usuario\":\"" + usuario + "\",\"password\":\"" + password + "\"}";
        URL url = new URL(urlBase + "/api/register");
        HttpURLConnection con = (HttpURLConnection) url.openConnection();
        con.setRequestMethod("POST");
        con.setRequestProperty("Content-Type", "application/json");
        con.setDoOutput(true);

        con.getOutputStream().write(json.getBytes());
        int codigoRespuesta = con.getResponseCode();
        con.disconnect();

        if (codigoRespuesta == 201) {
            System.out.println("Usuario registrado.");
        } else if (codigoRespuesta == 400) {
            System.out.println("Usuario ya existe.");
        }
    }

    private static void login() throws Exception {
        String usuario = "testuser";
        String password = "testpass123";

        String json = "{\"usuario\":\"" + usuario + "\",\"password\":\"" + password + "\"}";
        URL url = new URL(urlBase + "/api/login");
        HttpURLConnection con = (HttpURLConnection) url.openConnection();
        con.setRequestMethod("POST");
        con.setRequestProperty("Content-Type", "application/json");
        con.setDoOutput(true);

        con.getOutputStream().write(json.getBytes());
        int codigoRespuesta = con.getResponseCode();

        if (codigoRespuesta == 200) {
            BufferedReader br = new BufferedReader(new InputStreamReader(con.getInputStream()));
            StringBuilder respuesta = new StringBuilder();
            String linea;
            while ((linea = br.readLine()) != null) {
                respuesta.append(linea);
            }
            br.close();

            String resp = respuesta.toString();
            int inicio = resp.indexOf("\"token\":\"") + 9;
            int fin = resp.indexOf("\"", inicio);
            token = resp.substring(inicio, fin);
            System.out.println("Token obtenido.");
        }

        con.disconnect();
    }

    private static void obtenerSaldoInicial() throws Exception {
        try {
            URL url = new URL(urlBase + "/api/estado");
            HttpURLConnection con = (HttpURLConnection) url.openConnection();
            con.setRequestMethod("GET");
            con.setConnectTimeout(3000);
            con.setReadTimeout(3000);

            int codigoRespuesta = con.getResponseCode();
            if (codigoRespuesta == 200) {
                BufferedReader br = new BufferedReader(new InputStreamReader(con.getInputStream()));
                StringBuilder respuesta = new StringBuilder();
                String linea;
                while ((linea = br.readLine()) != null) {
                    respuesta.append(linea);
                }
                br.close();

                String resp = respuesta.toString();
                int inicio = resp.indexOf("\"saldoTotal\":") + 13;
                int fin = resp.indexOf(",", inicio);
                if (fin == -1) fin = resp.indexOf("}", inicio);
                saldoInicial = Double.parseDouble(resp.substring(inicio, fin).trim());
                System.out.println("Saldo total inicial: $" + saldoInicial);
            }
            con.disconnect();
        } catch (Exception e) {
            System.out.println("Error obteniendo saldo inicial");
        }
    }

private static void ejecutarPrueba() throws InterruptedException {
    System.out.println("Realizando transferencia");
    hacerTransferencia();
    System.out.println("Transferencia completada.");
    Thread.sleep(3000);
}

    private static void hacerLectura() {
        try {
            int id = idsValidos.get(random.nextInt(idsValidos.size()));
            URL url = new URL(urlBase + "/api/accounts/" + id);
            HttpURLConnection con = (HttpURLConnection) url.openConnection();
            con.setRequestMethod("GET");
            con.setRequestProperty("Authorization", "Bearer " + token);
            con.setConnectTimeout(3000);
            con.setReadTimeout(3000);

            int codigoRespuesta = con.getResponseCode();
            if (codigoRespuesta == 200) {
                lecturaExitosa.incrementAndGet();
                BufferedReader br = new BufferedReader(new InputStreamReader(con.getInputStream()));
                br.readLine();
                br.close();
            } else {
                errorLectura.incrementAndGet();
            }
            con.disconnect();
        } catch (Exception e) {
            errorLectura.incrementAndGet();
        }
    }

    private static void hacerTransferencia() {
        try {
            int source = idsValidos.get(random.nextInt(idsValidos.size()));
            int target = idsValidos.get(random.nextInt(idsValidos.size()));

            if (source == target) {
                return;
            }

            double amount = random.nextDouble() * 100 + 1;
            amount = Math.round(amount * 100.0) / 100.0;

            String json = "{\"sourceAccountId\":\"" + source + "\",\"targetAccountId\":\"" + target + "\",\"amount\":" + amount + "}";
            URL url = new URL(urlBase + "/api/transactions/transfer");
            HttpURLConnection con = (HttpURLConnection) url.openConnection();
            con.setRequestMethod("POST");
            con.setRequestProperty("Content-Type", "application/json");
            con.setRequestProperty("Authorization", "Bearer " + token);
            con.setDoOutput(true);
            con.setConnectTimeout(60000);
            con.setReadTimeout(60000);

            con.getOutputStream().write(json.getBytes());
            int codigoRespuesta = con.getResponseCode();

            if (codigoRespuesta == 200) {
                transferenciasExitosa.incrementAndGet();
                BufferedReader br = new BufferedReader(new InputStreamReader(con.getInputStream()));
                br.readLine();
                br.close();
            } else {
                errorTransferencia.incrementAndGet();
            }
            con.disconnect();
        } catch (Exception e) {
            errorTransferencia.incrementAndGet();
        }
    }

    private static void verificarConsistencia() {
        try {
            URL url = new URL(urlBase + "/api/estado");
            HttpURLConnection con = (HttpURLConnection) url.openConnection();
            con.setRequestMethod("GET");
            con.setConnectTimeout(3000);
            con.setReadTimeout(3000);

            int codigoRespuesta = con.getResponseCode();
            if (codigoRespuesta == 200) {
                BufferedReader br = new BufferedReader(new InputStreamReader(con.getInputStream()));
                StringBuilder respuesta = new StringBuilder();
                String linea;
                while ((linea = br.readLine()) != null) {
                    respuesta.append(linea);
                }
                br.close();

                String resp = respuesta.toString();
                int inicio = resp.indexOf("\"saldoTotal\":") + 13;
                int fin = resp.indexOf(",", inicio);
                if (fin == -1) fin = resp.indexOf("}", inicio);
                double saldoFinal = Double.parseDouble(resp.substring(inicio, fin).trim());

                System.out.println("Saldo total final: $" + saldoFinal);

                if (Math.abs(saldoFinal - saldoInicial) < 0.01) {
                    System.out.println("Consistencia verificada. Los saldos coinciden.");
                    System.out.println("\nScore final: " + (transferenciasExitosa.get() * 4 + lecturaExitosa.get()));
                } else {
                    System.out.println("INCONSISTENCIA DETECTADA!");
                    System.out.println("Diferencia: $" + (saldoFinal - saldoInicial));
                }
            }
            con.disconnect();
        } catch (Exception e) {
            System.out.println("Error verificando consistencia: " + e.getMessage());
        }
    }
}
