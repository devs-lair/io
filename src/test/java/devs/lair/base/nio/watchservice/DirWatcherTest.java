package devs.lair.base.nio.watchservice;

import org.apache.commons.lang3.reflect.FieldUtils;
import org.junit.jupiter.api.*;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;
import org.mockito.Mockito;

import java.io.IOException;
import java.nio.file.*;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.*;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class DirWatcherTest {
    private final String WATCH_DIR = "./src/main/java/devs/lair/base/nio/watchservice/watchdir";

    private Path watchDir;

    @BeforeAll
    void setUp() throws IOException {
        watchDir = Paths.get(WATCH_DIR);
        if (!Files.exists(watchDir)) {
            Files.createDirectory(watchDir);
        }
    }

    @AfterEach
    void afterEach() {
        Mockito.clearAllCaches();
    }

    @Test
    @DisplayName("Основной тест работы")
    void commonTest() throws IOException {
        final int IO_TIMEOUT = 50;
        Path testFilePath = Paths.get(watchDir + "/test.file");
        Path testDirPath = Paths.get(watchDir + "/testdir");

        if (Files.exists(testFilePath)) {
            Files.delete(testFilePath);
        }

        if (Files.exists(testDirPath)) {
            Files.delete(testDirPath);
        }

        DirListener listener = Mockito.spy(DirListener.class);
        try (DirWatcher watcher = new DirWatcher(WATCH_DIR)) {
            watcher.addListener(listener);
            watcher.startWatch();

            //create file
            Files.createFile(testFilePath);
            Mockito.verify(listener, Mockito.timeout(IO_TIMEOUT).times(1))
                    .onCreate(any());
            Mockito.reset(listener);

            //create dir
            Files.createDirectory(testDirPath);
            Mockito.verify(listener, Mockito.timeout(IO_TIMEOUT).times(1))
                    .onCreate(any());
            Mockito.reset(listener);

            //modify dir
            //why 2 times?
            Files.write(testFilePath, "TEST_TEXT".getBytes());
            Mockito.verify(listener, Mockito.timeout(IO_TIMEOUT).times(2))
                    .onModify(any());
            Mockito.reset(listener);

            //delete file
            ArgumentCaptor<Boolean> isDirectory = ArgumentCaptor.forClass(Boolean.class);
            Files.delete(testFilePath);
            Mockito.verify(listener, Mockito.timeout(IO_TIMEOUT).times(1))
                    .onDelete(any(), isDirectory.capture());
            assertThat(isDirectory.getValue()).isFalse();
            Mockito.reset(listener);

            //delete dir
            isDirectory = ArgumentCaptor.forClass(Boolean.class);
            Files.delete(testDirPath);
            Mockito.verify(listener, Mockito.timeout(IO_TIMEOUT).times(1))
                    .onDelete(any(), isDirectory.capture());
            assertThat(isDirectory.getValue()).isTrue();
            Mockito.reset(listener);

            watcher.stopWatch();
        }
    }

    @Test
    @DisplayName("Тригерим overflow")
    void overflowTest() throws InterruptedException, IllegalAccessException {
        final int IO_TIMEOUT = 50;

        DirListener listener = Mockito.spy(DirListener.class);
        try (DirWatcher watcher = new DirWatcher(WATCH_DIR)) {
            watcher.addListener(listener);

            WatchService mockWatchService = Mockito.mock(WatchService.class);
            Mockito.when(mockWatchService.take()).thenReturn(new OverflowWatchKey());

            FieldUtils.writeField(watcher, "watchService", mockWatchService, true);
            watcher.startWatch();
            Mockito.verify(listener, Mockito.timeout(IO_TIMEOUT).times(1))
                    .onOverflow(any());
            watcher.stopWatch();
        }
    }


    @Test
    @DisplayName("Удачное создание экземпляра, если директория существует")
    void positiveCreateInstance() {
        assertDoesNotThrow(() -> new DirWatcher(WATCH_DIR));
    }

    @Test
    @DisplayName("Проверка исключений при создании с неверным путем")
    @SuppressWarnings("resource")
    void negativeCreatedInstance() {
        assertThrows(IllegalArgumentException.class,
                () -> new DirWatcher("./not+_exist"),
                "Директории не существует, либо передан файл");
        assertThrows(IllegalArgumentException.class,
                () -> new DirWatcher("./pom.xml"),
                "Директории не существует, либо передан файл");
    }

    @Test
    @DisplayName("Исключение на newWatchService")
    @SuppressWarnings("resource")
    void negativeIOExceptionWhenNewWatchService() throws IOException {

        FileSystem fileSystem = Mockito.mock(FileSystem.class);
        Mockito.when(fileSystem.newWatchService()).thenThrow(new IOException());

        MockedStatic<Paths> paths = Mockito.mockStatic(Paths.class);
        paths.when(() -> Paths.get(anyString())).thenReturn(watchDir);

        MockedStatic<FileSystems> fileSystems = Mockito.mockStatic(FileSystems.class);
        fileSystems.when(FileSystems::getDefault).thenReturn(fileSystem);

        IllegalStateException exception = assertThrows(IllegalStateException.class,
                () -> new DirWatcher(WATCH_DIR));

        assertThat(exception.getMessage()).isEqualTo("Ошибка при создании WatchService");
    }

    @Test
    @DisplayName("Исключение на Files.walk")
    @SuppressWarnings("resource")
    void negativeIOExceptionWhenWalk() throws IOException {
        MockedStatic<Files> files = Mockito.mockStatic(Files.class);
        files.when(() -> Files.walk(any(), eq(1))).thenThrow(new IOException());
        files.when(() -> Files.isDirectory(any())).thenReturn(true);

        IllegalStateException exception = assertThrows(IllegalStateException.class,
                () -> new DirWatcher(WATCH_DIR));

        assertThat(exception.getMessage()).isEqualTo("Ошибка при получение поддиректорий");
    }

    @Test
    @DisplayName("Исключение на register")
    @SuppressWarnings("resource")
    void negativeIOExceptionWhenRegister() throws IOException {
        Path mockWatchDir = Mockito.mock(Path.class);
        Mockito.when(mockWatchDir.register(any(), any(WatchEvent.Kind[].class))).thenThrow(new IOException());

        MockedStatic<Paths> paths = Mockito.mockStatic(Paths.class);
        paths.when(() -> Paths.get(anyString())).thenReturn(mockWatchDir);

        MockedStatic<Files> files = Mockito.mockStatic(Files.class);
        files.when(() -> Files.walk(any(), eq(1))).thenReturn(Stream.of(watchDir));
        files.when(() -> Files.isDirectory(any())).thenReturn(true);

        IllegalStateException exception = assertThrows(IllegalStateException.class,
                () -> new DirWatcher(WATCH_DIR));

        assertThat(exception.getMessage()).isEqualTo("Ошибка при регистрации WatchService");
    }

    @Test
    @DisplayName("Идемпотентность close()")
    void checkCloseMethod() throws Exception {
        try (DirWatcher watcher = new DirWatcher(WATCH_DIR)) {
            assertThat(watcher.isClosed()).isFalse();

            assertDoesNotThrow(watcher::close);
            assertThat(watcher.isClosed()).isTrue();

            assertDoesNotThrow(watcher::close);
            assertThat(watcher.isClosed()).isTrue();

            assertDoesNotThrow(watcher::close);
            assertThat(watcher.isClosed()).isTrue();
        }
    }

    @Test
    @DisplayName("Exception на onClose")
    void exceptionOnClose() throws IOException, IllegalAccessException {
        WatchService mockService = Mockito.spy(WatchService.class);
        Mockito.doThrow(IOException.class).when(mockService).close();

        try (DirWatcher watcher = new DirWatcher(WATCH_DIR)) {
            assertThat(watcher.isClosed()).isFalse();
            FieldUtils.writeField(watcher, "watchService", mockService, true);
            IllegalStateException exception = assertThrows(IllegalStateException.class, watcher::close);
            assertThat(exception.getMessage()).isEqualTo("Ошибка при закрытии сервиса");
        }
    }

    @Test
    @DisplayName("Исключение когда стартуем/останавливаем уже закрытым")
    void exceptionWhenStartOnClose() {
        try (DirWatcher watcher = new DirWatcher(WATCH_DIR)) {
            assertThat(watcher.isStarted()).isFalse();
            assertThat(watcher.isClosed()).isFalse();
            watcher.close();

            assertThrows(IllegalStateException.class, watcher::startWatch);
            assertThrows(IllegalStateException.class, watcher::stopWatch);
        }
    }

    @Test
    @DisplayName("Добавление и удаление листенера")
    void testAddRemoveListener() {
        try (DirWatcher watcher = new DirWatcher(WATCH_DIR)) {
            DirListener listener = new DirListener();

            assertDoesNotThrow(() -> watcher.addListener(listener));
            boolean removed = watcher.removeListener(listener);
            assertThat(removed).isTrue();

            assertThrows(IllegalArgumentException.class, () -> watcher.addListener(null));
            assertThrows(IllegalArgumentException.class, () -> watcher.removeListener(null));
        }
    }

    @Test
    @DisplayName("Старт, стоп тест")
    void startStopWork() {
        try (DirWatcher watcher = new DirWatcher(WATCH_DIR)) {
            //start
            assertDoesNotThrow(watcher::startWatch);
            assertThat(watcher.isStarted()).isTrue();

            //start twice
            assertThrows(IllegalStateException.class, watcher::startWatch);

            //stop
            assertDoesNotThrow(watcher::stopWatch);
            assertThat(watcher.isStarted()).isFalse();

            //stop twice
            assertDoesNotThrow(watcher::stopWatch);

            //start again
            assertDoesNotThrow(watcher::startWatch);
            assertThat(watcher.isStarted()).isTrue();
        }
    }

    static class DirListener implements DirWatcher.DirWatcherListener {

        @Override
        public void onOverflow(WatchEvent<?> event) {
            System.out.println(event.kind() + " " + event.context());
        }

        @Override
        public void onCreate(WatchEvent<Path> event) {
            System.out.println(event.kind() + " " + event.context());
        }

        @Override
        public void onModify(WatchEvent<Path> event) {
            System.out.println(event.kind() + " " + event.context());
        }

        @Override
        public void onDelete(WatchEvent<Path> event, boolean isDirectory) {
            System.out.println(event.kind() + " " + event.context());
        }
    }

    static class OverflowWatchKey implements WatchKey {
        @Override
        public boolean isValid() {
            return false;
        }

        @Override
        public List<WatchEvent<?>> pollEvents() {
            return List.of(new WatchEvent<Object>() {
                @Override
                public Kind<Object> kind() {
                    return StandardWatchEventKinds.OVERFLOW;
                }

                @Override
                public int count() {
                    return 0;
                }

                @Override
                public Object context() {
                    return null;
                }
            });
        }

        @Override
        public boolean reset() {
            return false;
        }

        @Override
        public void cancel() {
        }

        @Override
        public Watchable watchable() {
            return null;
        }
    }
}