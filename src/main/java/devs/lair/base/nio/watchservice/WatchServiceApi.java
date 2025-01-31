package devs.lair.base.nio.watchservice;

import java.io.IOException;
import java.nio.file.*;

public class WatchServiceApi {

    public static void main(String[] args) {
        //Целевая директория
        Path watchDir = Paths.get("./src/main/java/devs/lair/base/nio/watchservice/watchdir");

        //Проверка директории
        if (!Files.exists(watchDir)) {
            System.out.println("Не найдена директория " + watchDir);
            return;
        }

        if (!Files.isDirectory(watchDir)) {
            System.out.println("Работает только с файлом" + watchDir);
            return;
        }

        try (WatchService watchService = FileSystems.getDefault().newWatchService()){
            //Регистрируем
            watchDir.register(watchService,
                    StandardWatchEventKinds.ENTRY_CREATE,
                    StandardWatchEventKinds.ENTRY_DELETE,
                    StandardWatchEventKinds.ENTRY_MODIFY);

            //Бесконечный цикл получения изменений
            WatchKey key;
            while ((key = watchService.take()) != null) {
                for (WatchEvent<?> event : key.pollEvents()) {

                    //Если переполнение
                    if (event.kind().equals(StandardWatchEventKinds.OVERFLOW)) {
                        System.out.println("Переполнение очереди");
                        continue;
                    }

                    //Выводим информацию о событии
                    if (event.context() instanceof Path p) {
                        Path fromWatchRoot = Paths.get(watchDir + "/" + p);
                        System.out.printf("Событие %s, %s: %s \n",
                                event.kind(),
                                Files.isDirectory(fromWatchRoot) ? "Директория" : "Файл",
                                fromWatchRoot);
                    }
                }

                //!! Обязтальено ресетим ключ
                boolean valid = key.reset();
                if (!valid) {
                    return;
                }
            }
        } catch (IOException e) {
            System.out.println(e.getMessage());
        } catch (InterruptedException e) {
            System.out.println("Поток выполнения был прерван");
        }
    }
}
