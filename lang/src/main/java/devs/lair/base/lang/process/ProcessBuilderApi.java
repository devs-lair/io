package devs.lair.base.lang.process;

import java.io.*;
import java.util.List;
import java.util.stream.Collectors;

public class ProcessBuilderApi {

    public static void main(String[] args) throws IOException, InterruptedException {
        ProcessBuilder processBuilder = new ProcessBuilder("java", "-version");
        processBuilder.redirectErrorStream(true);
        processBuilder.inheritIO();
        Process process = processBuilder.start();

        List<String> results = readOutput(process.getInputStream());
        results.forEach(System.out::println);

        int exitCode = process.waitFor();
        System.out.println(exitCode);
    }

    private static List<String> readOutput(InputStream inputStream) throws IOException {
        try (BufferedReader output = new BufferedReader(new InputStreamReader(inputStream))) {
            return output.lines().collect(Collectors.toList());
        }
    }
}
