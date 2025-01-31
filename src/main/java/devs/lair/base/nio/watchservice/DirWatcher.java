package devs.lair.base.nio.watchservice;

import java.io.IOException;
import java.nio.file.*;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

import static java.nio.file.StandardWatchEventKinds.*;

public class DirWatcher implements AutoCloseable {
    private final List<DirWatcherListener> listeners = new ArrayList<>();
    private final WatchService watchService;
    private final Set<String> dirs = new HashSet<>();
    private final Path watchDir;

    private Thread watchedThread;
    private boolean isStopped = false;
    private boolean isClosed = false;

    /**
     * Утилитный класс для наблюдений за директорией. Внутри использует
     * {@link java.nio.file.WatchService}. Наблюдение идет в фоновом потоке.
     * Реагирует на все события: CREATE, MODIFY, DELETE, а также на OVERFLOW.
     * В качестве слушателя событий принимает {@link DirWatcherListener}
     *
     * @throws IllegalStateException на IOException
     * @param path путь до наблюдаемой папки
     */

    public DirWatcher(String path) {
        watchDir = Paths.get(path);
        if (!Files.isDirectory(watchDir)) {
            throw new IllegalArgumentException("Директории не существует, либо передан файл");
        }

        try (Stream<Path> dirsPath = Files.walk(watchDir, 1)
                .filter(Files::isDirectory).map(Path::getFileName)) {
            watchService = FileSystems.getDefault().newWatchService();
            watchDir.register(watchService, ENTRY_CREATE, ENTRY_DELETE, ENTRY_MODIFY);
            dirs.addAll(dirsPath.map(Path::toString).toList());
        } catch (IOException ex) {
            String methodName = ex.getStackTrace()[0].getMethodName();
            String errorMessage = switch (methodName) {
                case "walk" -> "Ошибка при получение поддиректорий";
                case "register" -> "Ошибка при регистрации WatchService";
                case "<init>" -> "Ошибка при создании WatchService"; // newWatchService()
                default -> ex.getMessage();
            };
            throw new IllegalStateException(errorMessage, ex);
        }
    }

    /**
     * Основной рабочий метод. Запускает внутри себя новый поток,
     * который следит за директорией
     */

    public void startWatch() {
        checkIsClosed();

        if (watchedThread != null) {
            throw new IllegalStateException("Поток уже запущен");
        }

        isStopped = false;
        watchedThread = new Thread(() -> {
            WatchKey key;
            while (!isStopped) {
                try {
                    key = watchService.take();
                } catch (ClosedWatchServiceException | InterruptedException ex) {
                    break;
                }

                for (WatchEvent<?> event : key.pollEvents()) {
                    switch (event.kind().name()) {
                        case "ENTRY_CREATE" -> consumeCreateEvent(cast(event));
                        case "ENTRY_MODIFY" -> consumeModifyEvent(cast(event));
                        case "ENTRY_DELETE" -> consumeDeleteEvent(cast(event));
                        case "OVERFLOW" -> consumeOverflowEvent(event);
                    }
                }

                if (!key.reset()) {
                    break;
                }
            }
        });

        watchedThread.start();
    }

    /**
     * На OVERFLOW
     */

    private void consumeOverflowEvent(WatchEvent<?> watchEvent) {
        listeners.forEach(listener -> listener.onOverflow(watchEvent));
    }

    /**
     * На DELETE. В слушателей передается признак удаления директории,
     * если удалили директорию. Ввиду того, что узел файловой системы уже отсутствует,
     * нельзя определить, что удалили, файл или директорию, основываясь на передаваемом
     * событии. Для этих целей используется внутренний Set из имен директорий
     */

    private void consumeDeleteEvent(WatchEvent<Path> watchEvent) {
        Path pathFromRoot = Paths.get(watchDir + "/" + watchEvent.context());
        String dirName = pathFromRoot.getFileName().toString();
        boolean dirDeleted = dirs.remove(dirName);

        listeners.forEach(listener ->
                listener.onDelete(watchEvent, dirDeleted));
    }

    /**
     * На MODIFY
     */

    private void consumeModifyEvent(WatchEvent<Path> watchEvent) {
        listeners.forEach(listener -> listener.onModify(watchEvent));
    }

    /**
     * На CREATE. Дополнительно, если была создана директория, то ее название для учета
     * попадает во внутренний Set
     */

    private void consumeCreateEvent(WatchEvent<Path> watchEvent) {
        Path pathFromRoot = Paths.get(watchDir + "/" + watchEvent.context());
        if (Files.isDirectory(pathFromRoot)) {
            dirs.add(pathFromRoot.getFileName().toString());
        }

        listeners.forEach(listener ->
                listener.onCreate(watchEvent));
    }


    /**
     * Останавливает процесс (и поток) наблюдения. Возможен повторный перезапуск
     * через {@link DirWatcher#startWatch()}

     */

    public void stopWatch() {
        checkIsClosed();
        isStopped = true;
        if (watchedThread != null) {
            watchedThread.interrupt();
            watchedThread = null;
        }
    }

    public void addListener(DirWatcherListener listener) {
        if (listener == null) {
            throw new IllegalArgumentException("Передан null");
        }
        listeners.add(listener);
    }

    public boolean removeListener(DirWatcherListener listener) {
        if (listener == null) {
            throw new IllegalArgumentException("Передан null");
        }
        return listeners.remove(listener);
    }

    @Override
    public void close() {
        if (isClosed) {
            return;
        }

        try {
            stopWatch();
            watchService.close();
        } catch (IOException e) {
            throw new IllegalStateException("Ошибка при закрытии сервиса", e);
        } finally {
            isClosed = true;
        }
    }

    public boolean isStarted() {
        return watchedThread != null;
    }

    public boolean isClosed() {
        return isClosed;
    }

    private void checkIsClosed() {
        if (isClosed) {
            throw new IllegalStateException("Данный экземпляр уже был закрыт");
        }
    }

    @SuppressWarnings("unchecked")
    static WatchEvent<Path> cast(WatchEvent<?> event) {
        return (WatchEvent<Path>) event;
    }

    public interface DirWatcherListener {
        default void onOverflow(WatchEvent<?> event) {
        }

        default void onCreate(WatchEvent<Path> event) {
        }

        default void onModify(WatchEvent<Path> event) {
        }

        default void onDelete(WatchEvent<Path> event, boolean isDirectory) {
        }
    }
}

