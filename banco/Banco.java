import com.sun.net.httpserver.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;

public class Banco {
    private static Map<Integer, Cuenta> cuentas = new ConcurrentHashMap<>();
    private static Map<String, String> usuarios = new ConcurrentHashMap<>();
    private static int nodoId;
    private static int puerto;
    private static List<String> otrosNodos = new ArrayList<>();
    private static int numeroTransaccion = 0;
    private static List<String> logTransacciones = new CopyOnWriteArrayList<>();
    private static Object lockTransacciones = new Object();
    private static String rutaLogs = "logs_nodo_";

    static class Cuenta {
        int id;
        String propietario;
        double balance;

        Cuenta(int id, String propietario, double balance) {
            this.id = id;
            this.propietario = propietario;
            this.balance = balance;
        }
    }

    public static void main(String[] args) throws Exception {
        if (args.length < 3) {
            System.out.println("Uso: java Banco <nodoId> <puerto> <archivo_csv>");
            System.exit(1);
        }

        nodoId = Integer.parseInt(args[0]);
        puerto = Integer.parseInt(args[1]);
        String archivoCsv = args[2];
        rutaLogs = "logs_nodo_" + nodoId;

        if (nodoId == 1) {
            otrosNodos.add("http://localhost:7001");
            otrosNodos.add("http://localhost:7002");
        } else if (nodoId == 2) {
            otrosNodos.add("http://localhost:7000");
            otrosNodos.add("http://localhost:7002");
        } else if (nodoId == 3) {
            otrosNodos.add("http://localhost:7000");
            otrosNodos.add("http://localhost:7001");
        }

        cargarCuentas(archivoCsv);
        cargarLogTransacciones();

        HttpServer server = HttpServer.create(new InetSocketAddress(puerto), 0);
        server.createContext("/api/register", Banco::manejarRegister);
        server.createContext("/api/login", Banco::manejarLogin);
        server.createContext("/api/accounts/", Banco::manejarGetCuenta);
        server.createContext("/api/transactions/transfer", Banco::manejarTransferencia);
        server.createContext("/api/estado", Banco::manejarEstado);
        server.createContext("/api/sincronizar", Banco::manejarSincronizar);

        server.setExecutor(Executors.newFixedThreadPool(10));
        server.start();

        System.out.println("Nodo " + nodoId + " iniciado en puerto " + puerto);
    }

    private static void cargarCuentas(String archivo) {
        try (BufferedReader br = new BufferedReader(new FileReader(archivo))) {
            String linea;
            while ((linea = br.readLine()) != null) {
                String[] partes = linea.split(",");
                if (partes.length >= 3) {
                    int id = Integer.parseInt(partes[0]);
                    String nombre = partes[1];
                    double balance = Double.parseDouble(partes[2]);
                    cuentas.put(id, new Cuenta(id, nombre, balance));
                }
            }
            System.out.println("Cargadas " + cuentas.size() + " cuentas.");
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private static void manejarRegister(HttpExchange exchange) throws IOException {
        if (!exchange.getRequestMethod().equals("POST")) {
            enviarRespuesta(exchange, 405, "Método no permitido");
            return;
        }

        String body = leerBody(exchange);
        Map<String, String> datos = parsearJson(body);
        String usuario = datos.get("usuario");
        String password = datos.get("password");

        if (usuario == null || password == null) {
            enviarRespuesta(exchange, 400, "{\"error\":\"usuario y password requeridos\"}");
            return;
        }

        if (usuarios.containsKey(usuario)) {
            enviarRespuesta(exchange, 400, "{\"error\":\"usuario ya existe\"}");
            return;
        }

        usuarios.put(usuario, password);
        enviarRespuesta(exchange, 201, "{\"mensaje\":\"usuario registrado\"}");
    }

    private static void manejarLogin(HttpExchange exchange) throws IOException {
        if (!exchange.getRequestMethod().equals("POST")) {
            enviarRespuesta(exchange, 405, "Método no permitido");
            return;
        }

        String body = leerBody(exchange);
        Map<String, String> datos = parsearJson(body);
        String usuario = datos.get("usuario");
        String password = datos.get("password");

        if (!usuarios.containsKey(usuario) || !usuarios.get(usuario).equals(password)) {
            enviarRespuesta(exchange, 401, "{\"error\":\"credenciales inválidas\"}");
            return;
        }

        String token = generarJWT(usuario);
        enviarRespuesta(exchange, 200, "{\"token\":\"" + token + "\"}");
    }

    private static void manejarGetCuenta(HttpExchange exchange) throws IOException {
        if (!exchange.getRequestMethod().equals("GET")) {
            enviarRespuesta(exchange, 405, "Método no permitido");
            return;
        }

        if (!validarJWT(exchange)) {
            enviarRespuesta(exchange, 401, "{\"error\":\"no autorizado\"}");
            return;
        }

        String path = exchange.getRequestURI().getPath();
        String[] partes = path.split("/");
        int id = Integer.parseInt(partes[partes.length - 1]);

        Cuenta cuenta = cuentas.get(id);
        if (cuenta == null) {
            enviarRespuesta(exchange, 404, "{\"error\":\"cuenta no encontrada\"}");
            return;
        }

        String json = "{\"id\":" + cuenta.id + ",\"propietario\":\"" + cuenta.propietario + "\",\"balance\":" + cuenta.balance + "}";
        enviarRespuesta(exchange, 200, json);
    }

    private static void manejarTransferencia(HttpExchange exchange) throws IOException {
        if (!exchange.getRequestMethod().equals("POST")) {
            enviarRespuesta(exchange, 405, "Método no permitido");
            return;
        }

        if (!validarJWT(exchange)) {
            enviarRespuesta(exchange, 401, "{\"error\":\"no autorizado\"}");
            return;
        }

        String body = leerBody(exchange);
        Map<String, String> datos = parsearJson(body);
        int sourceId = Integer.parseInt(datos.get("sourceAccountId"));
        int targetId = Integer.parseInt(datos.get("targetAccountId"));
        double amount = Double.parseDouble(datos.get("amount"));

        synchronized (lockTransacciones) {
            Cuenta source = cuentas.get(sourceId);
            Cuenta target = cuentas.get(targetId);

            if (source == null || target == null) {
                enviarRespuesta(exchange, 404, "{\"error\":\"cuenta no encontrada\"}");
                return;
            }

            if (source.balance < amount) {
                enviarRespuesta(exchange, 400, "{\"error\":\"saldo insuficiente\"}");
                return;
            }

            numeroTransaccion++;
            int txId = numeroTransaccion;
            source.balance -= amount;
            target.balance += amount;

            String logEntry = txId + "|" + sourceId + "|" + targetId + "|" + amount;
            logTransacciones.add(logEntry);
            guardarLogTransaccion(logEntry);

            if (nodoId == 1) {
                replicarTransferencia(sourceId, targetId, amount, txId);
            }

            String respuesta = "{\"transactionId\":" + txId + ",\"message\":\"transferencia exitosa\"}";
            enviarRespuesta(exchange, 200, respuesta);
        }
    }

    private static void manejarEstado(HttpExchange exchange) throws IOException {
        double totalBalance = cuentas.values().stream().mapToDouble(c -> c.balance).sum();
        int numCuentas = cuentas.size();
        int numTransferencias = numeroTransaccion;
        int ultimaTransaccion = numeroTransaccion;

        String json = "{\"nodo\":" + nodoId + ",\"estado\":\"activo\",\"cuentas\":" + numCuentas
                + ",\"saldoTotal\":" + totalBalance + ",\"transferencias\":" + numTransferencias
                + ",\"ultimaTransaccion\":" + ultimaTransaccion + "}";
        enviarRespuesta(exchange, 200, json);
    }

    private static void manejarSincronizar(HttpExchange exchange) throws IOException {
        if (!exchange.getRequestMethod().equals("POST")) {
            enviarRespuesta(exchange, 405, "Método no permitido");
            return;
        }

        String body = leerBody(exchange);
        Map<String, String> datos = parsearJson(body);
        int desdeTransaccion = Integer.parseInt(datos.getOrDefault("desdeTransaccion", "0"));

        List<String> transaccionesNecesarias = new ArrayList<>();
        for (String log : logTransacciones) {
            int txId = Integer.parseInt(log.split("\\|")[0]);
            if (txId > desdeTransaccion) {
                transaccionesNecesarias.add(log);
            }
        }

        StringBuilder json = new StringBuilder("{\"transacciones\":[");
        for (int i = 0; i < transaccionesNecesarias.size(); i++) {
            if (i > 0) json.append(",");
            String[] partes = transaccionesNecesarias.get(i).split("\\|");
            json.append("{\"id\":").append(partes[0]).append(",\"source\":").append(partes[1])
                    .append(",\"target\":").append(partes[2]).append(",\"amount\":").append(partes[3]).append("}");
        }
        json.append("]}");

        enviarRespuesta(exchange, 200, json.toString());
    }

    private static void replicarTransferencia(int sourceId, int targetId, double amount, int txId) {
        for (String nodo : otrosNodos) {
            new Thread(() -> {
                try {
                    URL url = new URL(nodo + "/api/sincronizar");
                    HttpURLConnection con = (HttpURLConnection) url.openConnection();
                    con.setRequestMethod("POST");
                    con.setRequestProperty("Content-Type", "application/json");
                    con.setDoOutput(true);

                    String json = "{\"sourceAccountId\":\"" + sourceId + "\",\"targetAccountId\":\"" + targetId
                            + "\",\"amount\":" + amount + ",\"txId\":" + txId + "}";
                    con.getOutputStream().write(json.getBytes());
                    con.getResponseCode();
                    con.disconnect();
                } catch (Exception e) {
                    System.out.println("Error replicando en " + nodo + ": " + e.getMessage());
                }
            }).start();
        }
    }

    private static void guardarLogTransaccion(String entrada) {
        try (FileWriter fw = new FileWriter(rutaLogs + ".txt", true)) {
            fw.write(entrada + "\n");
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private static void cargarLogTransacciones() {
        File file = new File(rutaLogs + ".txt");
        if (file.exists()) {
            try (BufferedReader br = new BufferedReader(new FileReader(file))) {
                String linea;
                while ((linea = br.readLine()) != null) {
                    String[] partes = linea.split("\\|");
                    if (partes.length >= 4) {
                        int txId = Integer.parseInt(partes[0]);
                        int sourceId = Integer.parseInt(partes[1]);
                        int targetId = Integer.parseInt(partes[2]);
                        double amount = Double.parseDouble(partes[3]);

                        Cuenta source = cuentas.get(sourceId);
                        Cuenta target = cuentas.get(targetId);
                        if (source != null && target != null && source.balance >= amount) {
                            source.balance -= amount;
                            target.balance += amount;
                            numeroTransaccion = Math.max(numeroTransaccion, txId);
                            logTransacciones.add(linea);
                        }
                    }
                }
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
    }

    private static String generarJWT(String usuario) {
        long ahora = System.currentTimeMillis();
        long expiracion = ahora + 86400000;
        String payload = "{\"usuario\":\"" + usuario + "\",\"exp\":" + expiracion + "}";
        return Base64.getEncoder().encodeToString(payload.getBytes());
    }

    private static boolean validarJWT(HttpExchange exchange) {
        String header = exchange.getRequestHeaders().getFirst("Authorization");
        return header != null && header.startsWith("Bearer ");
    }

    private static String leerBody(HttpExchange exchange) throws IOException {
        BufferedReader br = new BufferedReader(new InputStreamReader(exchange.getRequestBody(), StandardCharsets.UTF_8));
        StringBuilder sb = new StringBuilder();
        String linea;
        while ((linea = br.readLine()) != null) {
            sb.append(linea);
        }
        return sb.toString();
    }

    private static Map<String, String> parsearJson(String json) {
        Map<String, String> map = new HashMap<>();
        json = json.replace("{", "").replace("}", "").replace("\"", "");
        String[] pares = json.split(",");
        for (String par : pares) {
            String[] keyValue = par.split(":");
            if (keyValue.length == 2) {
                map.put(keyValue[0].trim(), keyValue[1].trim());
            }
        }
        return map;
    }

    private static void enviarRespuesta(HttpExchange exchange, int codigoHttp, String respuesta) throws IOException {
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(codigoHttp, respuesta.getBytes().length);
        exchange.getResponseBody().write(respuesta.getBytes());
        exchange.close();
    }
}