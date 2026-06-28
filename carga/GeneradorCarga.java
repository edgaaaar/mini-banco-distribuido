import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

public class GeneradorCarga {
    private static String token = "";
    private static Map<Integer, Double> saldosIniciales = new HashMap<>();
    private static AtomicInteger lecturaExitosa = new AtomicInteger(0);
    private static AtomicInteger transferenciasExitosa = new AtomicInteger(0);
    private static AtomicInteger errorLectura = new AtomicInteger(0);
    private static AtomicInteger errorTransferencia = new AtomicInteger(0);
    private static String urlBase = "http://localhost:7000";
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

        System.out.println("Obteniendo saldos iniciales...");
        obtenerSaldosIniciales();
        double saldoInicial = saldosIniciales.values().stream().mapToDouble(Double::doubleValue).sum();
        System.out.println("Saldo total inicial: $" + saldoInicial);

        System.out.println("\nIniciando prueba de 60 segundos...");
        ejecutarPrueba();

        System.out.println("\n=== Resultados ===");
        System.out.println("Lecturas exitosas: " + lecturaExitosa.get());
        System.out.println("Transferencias exitosas: " + transferenciasExitosa.get());
        System.out.println("Errores en lecturas: " + errorLectura.get());
        System.out.println("Errores en transferencias: " + errorTransferencia.get());

        System.out.println("\nVerificando consistencia...");
        verificarConsistencia(saldoInicial);
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

    private static void obtenerSaldosIniciales() throws Exception {
        for (int id : idsValidos) {
            try {
                URL url = new URL(urlBase + "/api/accounts/" + id);
                HttpURLConnection con = (HttpURLConnection) url.openConnection();
                con.setRequestMethod("GET");
                con.setRequestProperty("Authorization", "Bearer " + token);
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
                    int inicio = resp.indexOf("\"balance\":") + 10;
                    int fin = resp.indexOf("}", inicio);
                    double balance = Double.parseDouble(resp.substring(inicio, fin).trim());
                    saldosIniciales.put(id, balance);
                }
                con.disconnect();
            } catch (Exception e) {
            }
        }
    }

    private static void ejecutarPrueba() throws InterruptedException {
        ExecutorService executor = Executors.newFixedThreadPool(20);
        long tiempoInicio = System.currentTimeMillis();
        long tiempoFin = tiempoInicio + 60000;

        while (System.currentTimeMillis() < tiempoFin) {
            executor.submit(() -> {
                int operacion = random.nextInt(100);
                if (operacion < 80) {
                    hacerLectura();
                } else {
                    hacerTransferencia();
                }
            });
        }

        executor.shutdown();
        executor.awaitTermination(65, TimeUnit.SECONDS);
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
            con.setConnectTimeout(3000);
            con.setReadTimeout(3000);

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

    private static void verificarConsistencia(double saldoInicial) {
        try {
            double saldoFinal = 0;
            for (int id : idsValidos) {
                URL url = new URL(urlBase + "/api/accounts/" + id);
                HttpURLConnection con = (HttpURLConnection) url.openConnection();
                con.setRequestMethod("GET");
                con.setRequestProperty("Authorization", "Bearer " + token);
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
                    int inicio = resp.indexOf("\"balance\":") + 10;
                    int fin = resp.indexOf("}", inicio);
                    double balance = Double.parseDouble(resp.substring(inicio, fin).trim());
                    saldoFinal += balance;
                }
                con.disconnect();
            }

            System.out.println("Saldo total final: $" + saldoFinal);

            if (Math.abs(saldoFinal - saldoInicial) < 0.01) {
                System.out.println("✓ Consistencia verificada. Los saldos coinciden.");
                System.out.println("\nScore: " + (transferenciasExitosa.get() * 4 + lecturaExitosa.get()));
            } else {
                System.out.println("✗ INCONSISTENCIA DETECTADA!");
                System.out.println("Diferencia: $" + (saldoFinal - saldoInicial));
            }
        } catch (Exception e) {
            System.out.println("Error verificando consistencia: " + e.getMessage());
        }
    }
}