import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class Main {
    static class Book {
        int id;
        String title;
        String author;
        boolean available = true;
        String borrowerName = "";
        String borrowerId = "";

        Book(int id, String title, String author) {
            this.id = id;
            this.title = title;
            this.author = author;
        }
    }

    private static final List<Book> books = new ArrayList<>();
    private static int nextId = 4;

    public static void main(String[] args) throws IOException {
        books.add(new Book(1, "Wings of Fire", "A. P. J. Abdul Kalam"));
        books.add(new Book(2, "The Alchemist", "Paulo Coelho"));
        books.add(new Book(3, "Introduction to Java", "Herbert Schildt"));

        // Render sets PORT. Locally, the application defaults to port 8080.
        int port = Integer.parseInt(
            System.getenv().getOrDefault("PORT", "8080"));

        // Listen on all interfaces so Render can reach the server.
        HttpServer server = HttpServer.create(
            new InetSocketAddress("0.0.0.0", port), 0);

        // Home page
        server.createContext("/", exchange -> {
            String path = exchange.getRequestURI().getPath();
            String method = exchange.getRequestMethod();

            if (!path.equals("/")) {
                send(exchange, 404, "text/plain; charset=UTF-8", "Page not found.");
                return;
            }
            if (!method.equals("GET") && !method.equals("HEAD")) {
                send(exchange, 405, "text/plain; charset=UTF-8", "Method not allowed.");
                return;
            }

            try {
                byte[] page = Files.readAllBytes(Path.of("index.html"));
                exchange.getResponseHeaders().set(
                    "Content-Type", "text/html; charset=UTF-8");
                exchange.getResponseHeaders().set("X-Content-Type-Options", "nosniff");

                if (method.equals("HEAD")) {
                    exchange.sendResponseHeaders(200, -1);
                    exchange.close();
                } else {
                    exchange.sendResponseHeaders(200, page.length);
                    try (OutputStream out = exchange.getResponseBody()) {
                        out.write(page);
                    }
                }
            } catch (IOException e) {
                if (!exchange.getResponseHeaders().containsKey("Content-Type")) {
                    send(exchange, 500, "text/plain; charset=UTF-8",
                        "Cannot read index.html. Check the project folder.");
                } else {
                    exchange.close();
                }
            }
        });

        // Send book data to the webpage as JSON
        server.createContext("/books", exchange -> {
            if (!exchange.getRequestMethod().equals("GET")) {
                send(exchange, 405, "text/plain; charset=UTF-8", "Method not allowed.");
                return;
            }

            StringBuilder json = new StringBuilder("[");
            synchronized (books) {
                for (int i = 0; i < books.size(); i++) {
                    Book b = books.get(i);
                    if (i > 0) json.append(",");
                    json.append("{")
                        .append("\"id\":").append(b.id).append(",")
                        .append("\"title\":\"").append(jsonEscape(b.title)).append("\",")
                        .append("\"author\":\"").append(jsonEscape(b.author)).append("\",")
                        .append("\"available\":").append(b.available).append(",")
                        .append("\"borrowerName\":\"").append(jsonEscape(b.borrowerName)).append("\",")
                        .append("\"borrowerId\":\"").append(jsonEscape(b.borrowerId)).append("\"")
                        .append("}");
                }
            }
            json.append("]");
            send(exchange, 200, "application/json; charset=UTF-8", json.toString());
        });

        // Add, borrow and return operations
        server.createContext("/action", exchange -> {
            if (!exchange.getRequestMethod().equals("GET")) {
                send(exchange, 405, "text/plain; charset=UTF-8", "Method not allowed.");
                return;
            }

            Map<String, String> params = parseQuery(
                exchange.getRequestURI().getRawQuery());
            String operation = params.getOrDefault("op", "");
            String message;
            boolean success = false;

            synchronized (books) {
                switch (operation) {
                    case "add" -> {
                        String title = params.getOrDefault("title", "").trim();
                        String author = params.getOrDefault("author", "").trim();
                        if (title.isEmpty() || author.isEmpty()
                                || title.length() > 100 || author.length() > 80) {
                            message = "Enter a valid book title and author.";
                        } else {
                            books.add(new Book(nextId++, title, author));
                            message = "Book added successfully!";
                            success = true;
                        }
                    }
                    case "borrow" -> {
                        String studentName = params.getOrDefault("studentName", "").trim();
                        String studentId = params.getOrDefault("studentId", "").trim();
                        if (studentName.isEmpty() || studentId.isEmpty()
                                || studentName.length() > 80 || studentId.length() > 30) {
                            message = "Please enter a valid student name and ID.";
                            break;
                        }

                        Book found = findBook(params.get("id"));
                        if (found == null) {
                            message = "Book ID not found.";
                        } else if (!found.available) {
                            message = "This book is already borrowed.";
                        } else {
                            found.borrowerName = studentName;
                            found.borrowerId = studentId;
                            found.available = false;
                            message = "Book borrowed successfully by " + studentName
                                + " (Student ID: " + studentId + ").";
                            success = true;
                        }
                    }
                    case "return" -> {
                        Book found = findBook(params.get("id"));
                        if (found == null) {
                            message = "Book ID not found.";
                        } else if (found.available) {
                            message = "This book is already available.";
                        } else {
                            found.available = true;
                            found.borrowerName = "";
                            found.borrowerId = "";
                            message = "Book returned successfully!";
                            success = true;
                        }
                    }
                    default -> message = "Unknown library operation.";
                }
            }

            String query = "message="
                + URLEncoder.encode(message, StandardCharsets.UTF_8)
                + "&success=" + success;
            exchange.getResponseHeaders().set("Location", "/?" + query);
            exchange.sendResponseHeaders(303, -1);
            exchange.close();
        });

        server.start();
        System.out.println("====================================");
        System.out.println(" Library Management System Started");
        System.out.println(" Listening on 0.0.0.0:" + port);
        System.out.println("====================================");
    }

    private static Book findBook(String idValue) {
        if (idValue == null) return null;
        try {
            int id = Integer.parseInt(idValue);
            for (Book book : books) {
                if (book.id == id) return book;
            }
        } catch (NumberFormatException e) {
            return null;
        }
        return null;
    }

    private static Map<String, String> parseQuery(String query) {
        Map<String, String> result = new HashMap<>();
        if (query == null) return result;

        for (String item : query.split("&")) {
            String[] pair = item.split("=", 2);
            if (pair.length == 2) {
                result.put(
                    URLDecoder.decode(pair[0], StandardCharsets.UTF_8),
                    URLDecoder.decode(pair[1], StandardCharsets.UTF_8));
            }
        }
        return result;
    }

    private static String jsonEscape(String value) {
        StringBuilder result = new StringBuilder();
        for (char c : value.toCharArray()) {
            switch (c) {
                case '"' -> result.append("\\\"");
                case '\\' -> result.append("\\\\");
                case '\n' -> result.append("\\n");
                case '\r' -> result.append("\\r");
                case '\t' -> result.append("\\t");
                default -> {
                    if (c < 0x20) {
                        result.append(String.format("\\u%04x", (int) c));
                    } else {
                        result.append(c);
                    }
                }
            }
        }
        return result.toString();
    }

    private static void send(HttpExchange exchange, int status,
            String contentType, String content) throws IOException {
        byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", contentType);
        exchange.getResponseHeaders().set("X-Content-Type-Options", "nosniff");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }
}
