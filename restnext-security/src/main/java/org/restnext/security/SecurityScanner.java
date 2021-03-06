/*
 * Copyright (C) 2016 Thiago Gutenberg Carvalho da Costa
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.restnext.security;

import org.restnext.core.http.codec.Request;
import org.restnext.core.classpath.ClasspathRegister;
import org.restnext.util.JsonUtils;
import io.netty.util.internal.SystemPropertyUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import pl.joegreen.lambdaFromString.LambdaFactory;
import pl.joegreen.lambdaFromString.LambdaFactoryConfiguration;
import pl.joegreen.lambdaFromString.TypeReference;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.*;
import java.util.*;
import java.util.function.Function;

import static org.restnext.util.FileUtils.listChildren;

/**
 * Created by thiago on 10/11/16.
 */
public final class SecurityScanner {

    private static final Logger log = LoggerFactory.getLogger(SecurityScanner.class);

    public static final Path DEFAULT_SECURITY_DIR = Paths.get(SystemPropertyUtil.get("user.dir"), "security");

    private final Security security;
    private final Path securityDirectory;
    private final Map<Path, Map<Path, Set<String>>> jarSecurityFileMap = new HashMap<>(); // the security jars metadata.

    private LambdaFactory lambdaFactory;

    // constructors

    public SecurityScanner(final Security security) {
        this(security, DEFAULT_SECURITY_DIR);
    }

    public SecurityScanner(final Security security, final Path securityDirectory) {
        Objects.requireNonNull(security, "Security must not be null");
        Objects.requireNonNull(securityDirectory, "Security directory must not be null");

        this.security = security;
        this.securityDirectory = securityDirectory;

        // start task for watching security dir for changes.
        new Thread(new SecurityWatcher(this), "security-dir-watcher").start();
    }

    // methods

    public void scan() {
        createLambda(listChildren(securityDirectory, "*.jar"));
    }

    void scan(final Path jar) {
        createLambda(Collections.singleton(jar));
    }

    void remove() {
        listChildren(securityDirectory, "*.jar").forEach(this::remove);
    }

    void remove(final Path jar) {
        Iterator<Map.Entry<Path, Map<Path, Set<String>>>> iterator = jarSecurityFileMap.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<Path, Map<Path, Set<String>>> jarEntry = iterator.next();
            Path jarfileNameRegistered = jarEntry.getKey();
            if (jarfileNameRegistered.equals(jar.getFileName())) {
                for (Map.Entry<Path, Set<String>> securityFileEntry : jarEntry.getValue().entrySet()) {
                    securityFileEntry.getValue().forEach(security::unregister);
                    iterator.remove();
                }
            }
        }
    }

    // getters methods

    Path getSecurityDirectory() {
        return securityDirectory;
    }

    // private methods

    private void createLambda(final Set<Path> jars) {
        String classpath = SystemPropertyUtil.get("java.class.path");
        StringJoiner compilationClassPathJoiner = new StringJoiner(":")
                .add(classpath);

        jars.forEach(jar -> {
            ClasspathRegister.addPath(jar);
            compilationClassPathJoiner.add(jar.toAbsolutePath().toString());
        });

        this.lambdaFactory = LambdaFactory.get(LambdaFactoryConfiguration.get()
                .withCompilationClassPath(compilationClassPathJoiner.toString())
                .withImports(Request.class));

        jars.forEach(this::lookupSecurityFiles);
    }

    private void lookupSecurityFiles(final Path jar) {
        try (FileSystem fs = FileSystems.newFileSystem(jar, null)) {
            final Path internalSecurityDir = fs.getPath("/META-INF/security/");
            if (Files.exists(internalSecurityDir)) {
                Set<Path> internalSecurityFiles = listChildren(internalSecurityDir, "*.json");
                Map<Path, Set<String>> securityFileUriMap = new HashMap<>(); // the jars security json metadata.
                internalSecurityFiles.forEach(securityFile -> {
                    Path securityFilename = securityFile.getFileName();
                    Set<String> securityUris = securityFileUriMap.get(securityFilename);
                    if (securityUris == null)
                        securityFileUriMap.put(securityFilename, readAndRegister(securityFile));
                    else
                        log.warn("Duplicated security file {} found in the same jar {}", securityFile, jar);
                });
                jarSecurityFileMap.put(jar.getFileName(), securityFileUriMap);
            }
        } catch (IOException ignore) {}
    }

    private Set<String> readAndRegister(final Path securityFile) {
        Set<String> registeredUris = new HashSet<>();
        try (InputStream is = Files.newInputStream(securityFile)) {
            final SecurityScanner.Metadata[] securitiesMetadata = JsonUtils.fromJson(is, SecurityScanner.Metadata[].class);

            for (SecurityScanner.Metadata securityMetadata : securitiesMetadata) {
                // required metadata.
                final String uri = securityMetadata.getUri();
                final String provider = securityMetadata.getProvider();

                // verify if some required metadata was informed, otherwise ignore.
                // TODO maybe in the future validate it with some json schema implementation.
                if (uri == null && provider == null) continue;

                // optional metadata.
                final boolean enable = securityMetadata.isEnable();

                /*
                  https://github.com/greenjoe/lambdaFromString#code-examples:
                  The compilation process takes time (on my laptop: first call ~1s, subsequent calls ~0.1s)
                  so it probably should not be used in places where performance matters.
                  The library is rather intended to be used once during the configuration reading process when
                  the application starts.
                */
                Function<Request, Boolean> securityProvider = lambdaFactory.createLambdaUnchecked(provider, new TypeReference<Function<Request,Boolean>>() {});

                // verify if the uri is already registered by another json file inside some jar file.
                boolean uriAlreadyRegistered = false;
                Path jarFilenameRegistered = null, securityFilenameRegistered = null;
                for (Map.Entry<Path, Map<Path, Set<String>>> jarEntry : jarSecurityFileMap.entrySet()) {
                    jarFilenameRegistered = jarEntry.getKey();
                    for (Map.Entry<Path, Set<String>> securityFileEntry : jarEntry.getValue().entrySet()) {
                        securityFilenameRegistered = securityFileEntry.getKey();
                        uriAlreadyRegistered = !securityFilenameRegistered.equals(securityFile.getFileName()) && securityFileEntry.getValue().contains(uri);
                    }
                }

                if (!uriAlreadyRegistered) {
                    security.register(Security.Mapping.uri(uri, securityProvider).enable(enable).build());
                    registeredUris.add(uri);
                } else {
                    log.warn("Uri {} already registered through the security file {} inside the jar {}", uri, securityFilenameRegistered, jarFilenameRegistered);
                }
            }
        } catch (IOException ignore) {}
        return registeredUris;
    }

    // inner json file metadata class

    private static final class Metadata {

        private String uri;
        private String provider;
        private boolean enable;

        public Metadata() {
            super();
        }

        public String getUri() {
            return uri;
        }

        public void setUri(String uri) {
            this.uri = uri;
        }

        public String getProvider() {
            return provider;
        }

        public void setProvider(String provider) {
            this.provider = provider;
        }

        public boolean isEnable() {
            return enable;
        }

        public void setEnable(boolean enable) {
            this.enable = enable;
        }
    }
}
