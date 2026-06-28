import com.sun.net.httpserver.*;
import java.io.*;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.concurrent.Executors;

public class ServidorDashboard {
    public static void main(String[] args) throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress(8080), 0);
        server.createContext("/", ServidorDashboard::manejarGetDashboard);
        server.createContext("/api/estado-nodos", ServidorDashboard::manejarEstadoNodos);
        server.setExecutor(Executors.newFixedThreadPool(5));
        server.start();
        System.out.println("Dashboard iniciado en http://localhost:8080");
    }

    private static void manejarGetDashboard(HttpExchange exchange) throws IOException {
        if (!exchange.getRequestURI().getPath().equals("/")) {
            exchange.sendResponseHeaders(404, 0);
            exchange.close();
            return;
        }

        String html = leerArchivo("dashboard.html");
        exchange.getResponseHeaders().set("Content-Type", "text/html; charset=UTF-8");
        exchange.sendResponseHeaders(200, html.getBytes(StandardCharsets.UTF_8).length);
        exchange.getResponseBody().write(html.getBytes(StandardCharsets.UTF_8));
        exchange.close();
    }

    private static void manejarEstadoNodos(HttpExchange exchange) throws IOException {
        StringBuilder respuesta = new StringBuilder("{\"nodos\":[");

        for (int i = 0; i < 3; i++) {
            int puerto = 7000 + i;
            String estado = obtenerEstadoNodo("http://localhost:" + puerto);
            if (i > 0) respuesta.append(",");
            respuesta.append(estado);
        }

        respuesta.append("]}");

        exchange.getResponseHeaders().set("Content-Type", "application/json");
        String json = respuesta.toString();
        exchange.sendResponseHeaders(200, json.getBytes().length);
        exchange.getResponseBody().write(json.getBytes());
        exchange.close();
    }

    private static String obtenerEstadoNodo(String url) {
        try {
            java.net.URL obj = new java.net.URL(url + "/api/estado");
            java.net.HttpURLConnection con = (java.net.HttpURLConnection) obj.openConnection();
            con.setRequestMethod("GET");
            con.setConnectTimeout(2000);
            con.setReadTimeout(2000);

            int codigoRespuesta = con.getResponseCode();
            if (codigoRespuesta == 200) {
                BufferedReader in = new BufferedReader(new InputStreamReader(con.getInputStream()));
                StringBuilder content = new StringBuilder();
                String linea;
                while ((linea = in.readLine()) != null) {
                    content.append(linea);
                }
                in.close();
                con.disconnect();
                return content.toString();
            } else {
                return "{\"nodo\":0,\"estado\":\"inactivo\"}";
            }
        } catch (Exception e) {
            return "{\"nodo\":0,\"estado\":\"inactivo\"}";
        }
    }

    private static String leerArchivo(String nombre) throws IOException {
        return new String(Files.readAllBytes(Paths.get(nombre)), StandardCharsets.UTF_8);
    }
}