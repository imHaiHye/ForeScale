package com.viralguard.component;

import lombok.extern.slf4j.Slf4j;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.Consumer;

/**
 * ProcessBuilder 프로세스의 stdout / stderr 스트림을 비동기로 소비하는 컴포넌트.
 *
 * ── 왜 반드시 필요한가 ──────────────────────────────────────────
 * OS의 파이프 버퍼(보통 4~64KB)가 가득 차면 자식 프로세스(Python)가 BLOCK됨.
 * 부모(JVM)가 process.waitFor()로 대기 중이면 → 데드락 발생.
 *
 * 해결책: stdout과 stderr를 별도 스레드에서 동시에 비워줘야 함.
 *
 * ── 사용법 ────────────────────────────────────────────────────
 * ProcessStreamGobbler stdout = new ProcessStreamGobbler(
 *     process.getInputStream(), "[boto3-stdout]", log::info
 * );
 * ProcessStreamGobbler stderr = new ProcessStreamGobbler(
 *     process.getErrorStream(), "[boto3-stderr]", log::warn
 * );
 * stdout.start();
 * stderr.start();
 * process.waitFor(30, TimeUnit.SECONDS);
 * stdout.join(2000);
 * stderr.join(2000);
 */
@Slf4j
public class ProcessStreamGobbler extends Thread {

    private final InputStream inputStream;
    private final String prefix;
    private final Consumer<String> lineConsumer;

    /** 소비한 모든 라인 보관 (결과 로깅 및 오류 분석용) */
    private final List<String> collectedLines = Collections.synchronizedList(new ArrayList<>());

    public ProcessStreamGobbler(InputStream inputStream, String prefix, Consumer<String> lineConsumer) {
        this.inputStream = inputStream;
        this.prefix = prefix;
        this.lineConsumer = lineConsumer;
        setDaemon(true);
        setName("stream-gobbler-" + prefix);
    }

    @Override
    public void run() {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(inputStream))) {
            String line;
            while ((line = reader.readLine()) != null) {
                collectedLines.add(line);
                lineConsumer.accept(prefix + " " + line);
            }
        } catch (IOException e) {
            // 프로세스 정상 종료 시 스트림이 닫히면서 발생하는 정상적인 예외
            if (!e.getMessage().contains("Stream closed")) {
                log.debug("[StreamGobbler] 스트림 읽기 종료: {}", e.getMessage());
            }
        }
    }

    /** 소비한 모든 출력 라인을 반환 */
    public List<String> getCollectedLines() {
        return Collections.unmodifiableList(collectedLines);
    }

    /** 마지막 N줄만 반환 (로그 요약용) */
    public List<String> getLastLines(int n) {
        List<String> lines = getCollectedLines();
        int from = Math.max(0, lines.size() - n);
        return lines.subList(from, lines.size());
    }
}
