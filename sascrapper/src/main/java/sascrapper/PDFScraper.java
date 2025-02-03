package sascrapper;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

public class PDFScraper {
    private static final int THREAD_COUNT = 50;
    private static final int MAX_RETRIES = 5;
    private static final int TIMEOUT = 60000;
    private static final OkHttpClient httpClient = new OkHttpClient();
    public static void main(String[] args) {
        String baseUrl = "https://papers.nips.cc";
        String outputDir = "B:/scraped-pdfs/";
        ExecutorService executor = Executors.newFixedThreadPool(THREAD_COUNT);
        try {
            System.out.println("Connecting to main page: " + baseUrl);
            Document document = Jsoup.connect(baseUrl).timeout(TIMEOUT).get();
            System.out.println("Successfully connected to main page.");
            Elements yearLinks = document.select("a[href^=/paper_files/paper/]");  // Extract all year links
            System.out.println("Found " + yearLinks.size() + " paper archive links.");         
            int latestYear = extractLatestYear(yearLinks);  // Extract the latest year from the website
            System.out.println("Latest year found: " + latestYear);
            Set<Integer> targetYears = new HashSet<>();// Calculate the past 5 years
            for (int i = 0; i < 5; i++) {
                targetYears.add(latestYear - i);  }
            int count = 0;
            for (Element yearLink : yearLinks) {  // Process only the past 5 years
                String yearUrl = baseUrl + yearLink.attr("href");
                int year = extractYearFromUrl(yearUrl);
                if (targetYears.contains(year)) {
                    System.out.println("Processing paper archive: " + yearUrl);
                    try {
                        Document yearPage = Jsoup.connect(yearUrl).timeout(TIMEOUT).get();
                        Elements paperLinks = yearPage.select("ul.paper-list li a[href$=Abstract-Conference.html]");

                        for (Element paperLink : paperLinks) {
                            String paperUrl = baseUrl + paperLink.attr("href");
                            executor.submit(() -> processPaper(baseUrl, paperUrl, outputDir)); }
                        count++;
                        if (count >= 5) {
                            break; // Stop after processing 5 years
                        }
                    } catch (IOException e) {
                        System.err.println("Failed to process year: " + yearUrl);
                        e.printStackTrace();}}
            }
            executor.shutdown();
            executor.awaitTermination(Long.MAX_VALUE, TimeUnit.NANOSECONDS);
        } catch (IOException | InterruptedException e) {
            System.err.println("An error occurred during the scraping process.");
            e.printStackTrace(); }
    }

    private static int extractLatestYear(Elements yearLinks) {  // Extract the latest year from the year links
        int latestYear = 0;
        for (Element yearLink : yearLinks) {
            String yearUrl = yearLink.attr("href");
            int year = extractYearFromUrl(yearUrl);
            if (year > latestYear) {
                latestYear = year;}
        }
        return latestYear;
    }

    private static int extractYearFromUrl(String yearUrl) {   // Extract the year from the URL
        try {
            String yearStr = yearUrl.replaceAll(".*/(\\d+)$", "$1"); // Extract the year from the URL (e.g., /paper_files/paper/2023)
            return Integer.parseInt(yearStr);
        } catch (NumberFormatException e) {
            System.err.println("Failed to extract year from URL: " + yearUrl);
            return 0; } //return 0 or valid url
    }

    private static void processPaper(String baseUrl, String paperUrl, String outputDir) {
        String threadId = Thread.currentThread().getName();
        int attempts = 0;
        boolean success = false;
        while (attempts < MAX_RETRIES && !success) {
            try {
                System.out.println(threadId + " - Processing paper: " + paperUrl + " (Attempt " + (attempts + 1) + ")");
                Document paperPage = Jsoup.connect(paperUrl).timeout(TIMEOUT).get();
                String paperTitle = sanitizeFilename(paperPage.select("title").text());
                Element pdfLink = paperPage.selectFirst("a[href$=Paper-Conference.pdf]");
                if (pdfLink != null) {
                    String pdfUrl = baseUrl + pdfLink.attr("href");
                    downloadPDF(pdfUrl, outputDir, paperTitle);
                    success = true;
                } else {
                    System.out.println(threadId + " - No PDF link found on: " + paperUrl);
                    success = true; }
            } catch (IOException e) {
                System.err.println("Failed to process paper: " + paperUrl + " (Attempt " + (attempts + 1) + ")");
                e.printStackTrace();
                attempts++;
                if (attempts >= MAX_RETRIES) {
                    System.err.println(threadId + " - Giving up on paper: " + paperUrl); }}}
    }
    
    private static void downloadPDF(String pdfUrl, String outputDir, String fileName) throws IOException {
        Request request = new Request.Builder().url(pdfUrl).build();
        try (Response response = httpClient.newCall(request).execute()) {
            if (response.isSuccessful() && response.body() != null) {
                String filePath = outputDir + fileName + ".pdf";
                Files.createDirectories(Paths.get(outputDir));
                try (InputStream inputStream = response.body().byteStream();
                     FileOutputStream outputStream = new FileOutputStream(filePath)) {
                    byte[] buffer = new byte[8192];
                    int bytesRead;
                    while ((bytesRead = inputStream.read(buffer)) != -1) {
                        outputStream.write(buffer, 0, bytesRead);}                            
                }System.out.println("Saved PDF: " + filePath);
            } else {
                throw new IOException("Failed to download PDF: " + pdfUrl);}}
    }

    private static String sanitizeFilename(String filename) {
        return filename.replaceAll("[\\\\/:*?\"<>|]", "_");
    }
}