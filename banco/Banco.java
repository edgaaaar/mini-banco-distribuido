import com.sun.net.httpserver.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
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
            otrosNodos.add("http://34.27.89.27:7001");
            otrosNodos.add("http://34.63.19.199:7002");
        } else if (nodoId == 2) {
            otrosNodos.add("http://35.238.193.18:7000");
            otrosNodos.add("http://34.63.19.199:7002");
        } else if (nodoId == 3) {
            otrosNodos.add("http://35.238.193.18:7000");
            otrosNodos.add("http://34.27.89.27:7001");
        }

        cargarCuentas(archivoCsv);
        cargarLogTransacciones();

        if (nodoId != 1) {
            sincronizarConLider();
        }

        HttpServer server = HttpServer.create(new InetSocketAddress(puerto), 0);
        server.createContext("/api/register", Banco::manejarRegister);
        server.createContext("/api/login", Banco::manejarLogin);
        server.createContext("/api/accounts/", Banco::manejarGetCuenta);
        server.createContext("/api/transactions/transfer", Banco::manejarTransferencia);
        server.createContext("/api/estado", Banco::manejarEstado);
        server.createContext("/api/replicar", Banco::manejarReplicar);
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

    private static void manejarReplicar(HttpExchange exchange) throws IOException {
        if (!exchange.getRequestMethod().equals("POST")) {
            enviarRespuesta(exchange, 405, "Método no permitido");
            return;
        }

        String body = leerBody(exchange);
        Map<String, String> datos = parsearJson(body);
        int sourceId = Integer.parseInt(datos.get("sourceAccountId"));
        int targetId = Integer.parseInt(datos.get("targetAccountId"));
        double amount = Double.parseDouble(datos.get("amount"));
        int txId = Integer.parseInt(datos.get("txId"));

        synchronized (lockTransacciones) {
            Cuenta source = cuentas.get(sourceId);
            Cuenta target = cuentas.get(targetId);

            if (source != null && target != null && source.balance >= amount) {
                source.balance -= amount;
                target.balance += amount;
                numeroTransaccion = Math.max(numeroTransaccion, txId);

                String logEntry = txId + "|" + sourceId + "|" + targetId + "|" + amount;
                logTransacciones.add(logEntry);
                guardarLogTransaccion(logEntry);

                enviarRespuesta(exchange, 200, "{\"mensaje\":\"replicado\"}");
            } else {
                enviarRespuesta(exchange, 400, "{\"error\":\"no se pudo replicar\"}");
            }
        }
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

    private static void replicarTransferencia(int sourceId, int targetId, double amount, int txId) {
        for (String nodo : otrosNodos) {
            new Thread(() -> {
                try {
                    URL url = new URL(nodo + "/api/replicar");
                    HttpURLConnection con = (HttpURLConnection) url.openConnection();
                    con.setRequestMethod("POST");
                    con.setRequestProperty("Content-Type", "application/json");
                    con.setDoOutput(true);
                    con.setConnectTimeout(3000);
                    con.setReadTimeout(3000);

                    String json = "{\"sourceAccountId\":\"" + sourceId + "\",\"targetAccountId\":\"" + targetId
                            + "\",\"amount\":" + amount + ",\"txId\":" + txId + "}";
                    con.getOutputStream().write(json.getBytes());
                    con.getResponseCode();
                    con.disconnect();
                } catch (Exception e) {
                }
            }).start();
        }
    }

    private static void sincronizarConLider() {
        System.out.println("Nodo " + nodoId + " sincronizándose con líder...");
        
        int miUltimoTxId = obtenerUltimoTxIdDelLog();
        System.out.println("Último txId en mi log: " + miUltimoTxId);
        
        try {
            URL url = new URL("http://35.238.193.18:7000/api/sincronizar");
            HttpURLConnection con = (HttpURLConnection) url.openConnection();
            con.setRequestMethod("POST");
            con.setRequestProperty("Content-Type", "application/json");
            con.setDoOutput(true);
            con.setConnectTimeout(5000);
            con.setReadTimeout(5000);
            
            String json = "{\"desdeTransaccion\":" + miUltimoTxId + "}";
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
                
                aplicarTransaccionesDelLider(respuesta.toString());
                System.out.println("Sincronización completada. Transacciones aplicadas: " + (numeroTransaccion - miUltimoTxId));
            } else {
                System.out.println("Error en sincronización. Código: " + codigoRespuesta);
            }
            con.disconnect();
        } catch (Exception e) {
            System.out.println("No se pudo sincronizar con líder: " + e.getMessage());
        }
    }

    private static int obtenerUltimoTxIdDelLog() {
        int maxTxId = 0;
        File file = new File(rutaLogs + ".txt");
        if (file.exists()) {
            try (BufferedReader br = new BufferedReader(new FileReader(file))) {
                String linea;
                while ((linea = br.readLine()) != null) {
                    String[] partes = linea.split("\\|");
                    if (partes.length >= 1) {
                        int txId = Integer.parseInt(partes[0]);
                        maxTxId = Math.max(maxTxId, txId);
                    }
                }
            } catch (IOException e) {
            }
        }
        return maxTxId;
    }

    private static void aplicarTransaccionesDelLider(String jsonRespuesta) {
        try {
            String cleanJson = jsonRespuesta.replace("{\"transacciones\":[", "").replace("]}", "");
            
            if (cleanJson.trim().isEmpty()) {
                System.out.println("No hay transacciones nuevas para sincronizar.");
                return;
            }
            
            String[] transacciones = cleanJson.split("\\},\\{");
            
            for (String tx : transacciones) {
                tx = tx.replace("{", "").replace("}", "");
                
                Map<String, String> datos = new HashMap<>();
                String[] pares = tx.split(",");
                for (String par : pares) {
                    String[] keyValue = par.split(":");
                    if (keyValue.length == 2) {
                        String key = keyValue[0].replace("\"", "").trim();
                        String value = keyValue[1].replace("\"", "").trim();
                        datos.put(key, value);
                    }
                }
                
                int txId = Integer.parseInt(datos.get("id"));
                int sourceId = Integer.parseInt(datos.get("source"));
                int targetId = Integer.parseInt(datos.get("target"));
                double amount = Double.parseDouble(datos.get("amount"));
                
                synchronized (lockTransacciones) {
                    Cuenta source = cuentas.get(sourceId);
                    Cuenta target = cuentas.get(targetId);
                    
                    if (source != null && target != null && source.balance >= amount) {
                        source.balance -= amount;
                        target.balance += amount;
                        numeroTransaccion = Math.max(numeroTransaccion, txId);
                        
                        String logEntry = txId + "|" + sourceId + "|" + targetId + "|" + amount;
                        logTransacciones.add(logEntry);
                        guardarLogTransaccion(logEntry);
                    }
                }
            }
        } catch (Exception e) {
            System.out.println("Error aplicando transacciones del líder: " + e.getMessage());
        }
    }

    private static void guardarLogTransaccion(String entrada) {
        try (FileWriter fw = new FileWriter(rutaLogs + ".txt", true)) {
            fw.write(entrada + "\n");
        } catch (IOException e) {
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