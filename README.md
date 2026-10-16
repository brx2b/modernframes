# ModernFrames

Marcos de imagen modernos para **Minecraft 1.21.1 (NeoForge)**: muestra imágenes desde
**URLs / APIs de imágenes** o desde **archivos locales**, con animación GIF, zoom, paneo,
rotación, opacidad, brillo y más.

> Mod nuevo y ligero, inspirado en el *comportamiento* de WATERFrAMES y LittleFrames, pero
> sin dependencias nativas (sin VLC/FFmpeg) y solo para imágenes (PNG, JPG, JPEG, GIF, BMP,
> WebP, TGA y PSD).

## Características

- **Fuentes remotas**: cualquier URL directa de imagen o endpoint de API (`http`/`https`).
- **Fuentes locales**: carpeta sandbox `config/modernframes/images` (o rutas absolutas si
  el servidor lo permite).
- **GIF animados** con compositing correcto (métodos de disposal, transparencia, retrasos).
- **WebP** (con y sin pérdida) decodificado en Java puro con la librería embebida
  TwelveMonkeys; los WebP animados se muestran como primer fotograma. También TGA, PSD,
  PNM y HDR a través del decodificador propio del juego.
- **Apariencia del marco**: borde, panel de fondo (oculta la pared tras la imagen, útil con
  opacidad baja), doble cara y modo "iluminado" (brillo total).
- **Ajustes de imagen**: zoom, desplazamiento X/Y, rotación, opacidad, brillo y espejo X/Y,
  con botón **Por defecto** para revertirlos todos.
- **Multi-cara**: pared, suelo y techo.
- **Multibloque**: el marco se expande horizontal y verticalmente, automáticamente según el
  formato de la imagen (*Ajustar al formato*) o a mano con clic derecho. Un solo objeto: al
  romper cualquier bloque cae un único ítem.
- **Rendimiento**: descarga/decodificación asíncrona (nunca bloquea el render), caché de
  texturas LRU en memoria, caché en disco para URLs, límites configurables de tamaño.
- **Seguridad**: políticas de servidor (URLs remotas, archivos locales, rutas absolutas,
  quién puede editar), validación anti path-traversal, límite de tamaño de descarga.
- **Distancia de render por marco**: cada marco decide a qué distancia se dibuja (8–256).

## Uso

1. Craftea el **Marco de imagen**: 8 palos alrededor de un panel de cristal.
2. Colócalo en una pared, suelo o techo.
3. Clic derecho para abrir la configuración:

| Pestaña | Qué hace |
|---|---|
| **URL** | Pega una URL de imagen (o usa *Pegar*) y pulsa *Ver vista previa*. |
| **Archivo** | Elige una imagen de `config/modernframes/images` (`<`/`>`), abre la carpeta o usa una ruta absoluta (si está permitida). |
| **Tamaño** | Ancho × alto en bloques y *Ajustar al formato* (auto-expansible según la imagen). |
| **Marco** | Borde, panel de fondo, doble cara, iluminado y distancia de render. |
| **Ajustes** | Zoom, rotación, paneo, opacidad, brillo y espejo X/Y + botón **Por defecto**. |

4. **Guardar** sincroniza la configuración al servidor y a todos los clientes (si el tamaño
   cambió, el marco se remodela en el mundo).
5. **Redimensionado rápido**: con el *Marco de imagen* en la mano, clic derecho sobre un lado
   del marco = ampliar hacia ese lado; agachado + clic derecho = reducir ese lado.

Cada jugador descarga la imagen de la URL en su propia máquina; para fuentes locales, cada
jugador necesita ese archivo en su propio `config/modernframes/images` (o ruta válida).

## Configuración

`config/modernframes-common.toml`:

| Clave | Defecto | Descripción |
|---|---|---|
| `remote.allowRemoteUrls` | `true` | Permitir fuentes http/https |
| `remote.maxDownloadKb` | `8192` | Tamaño máximo por imagen |
| `remote.downloadTimeoutSeconds` | `15` | Timeout HTTP |
| `local.allowLocalFiles` | `true` | Permitir archivos del sandbox |
| `local.allowAbsolutePaths` | `false` | Permitir rutas absolutas (⚠ solo mundos de confianza) |
| `permissions.editPolicy` | `OWNER` | `EVERYONE` / `CREATIVE_ONLY` / `OWNER` |
| `resize.maxSize` | `8` | Ancho y alto máximos del marco, en bloques |
| `resize.maxArea` | `64` | Área total máxima del marco, en bloques |

`config/modernframes-client.toml`:

| Clave | Defecto | Descripción |
|---|---|---|
| `images.maxCachedImages` | `64` | Texturas en memoria (LRU) |
| `images.maxTextureSize` | `1024` | Escalado máximo de imágenes estáticas |
| `images.smoothFiltering` | `true` | Filtro suave (desactivar para pixel-art) |
| `images.diskCache` | `true` | Caché en disco de descargas |
| `gifs.maxFrames` | `128` | Fotogramas máximos por GIF |
| `gifs.maxSize` | `512` | Escalado máximo de GIF |

## Compilar

Requiere **Java 21**. El wrapper de Gradle ya está incluido:

```powershell
.\gradlew.bat build        # compila y genera build/libs/modernframes-1.0.0.jar
.\gradlew.bat runClient    # abre un cliente de prueba
```

## Estado y hoja de ruta

**Versión 1.0.0.** Probado compilando contra NeoForge 21.1.244 (ModDevGradle 2.0.142).
Marcos multibloque con auto-ajuste al formato de la imagen, redimensionado por clic derecho
y por GUI. Ideas futuras: álbumes/playlists, comandos, API para otros mods.

## Hecho con IA

Este mod fue desarrollado con asistencia de **inteligencia artificial** (agentes de código
con modelos de lenguaje), siempre bajo dirección, pruebas en el juego y revisión humana. El
diseño, las decisiones y el control de calidad son del autor.

## Créditos y licencia

- Incluye (embebida vía JarJar) la librería **TwelveMonkeys ImageIO** para decodificar WebP
  (`com.twelvemonkeys.imageio:imageio-webp` y sus módulos, BSD 3-Clause, © Harald Kuhr;
  texto de licencia en `META-INF/THIRD-PARTY-NOTICES.txt` dentro del jar).
- Inspirado en el comportamiento de **WATERFrAMES** (de SrRapero720) y **LittleFrames**;
  **no se copió código**: solo se usaron como referencia funcional.
- Análisis del artefacto oficial realizado con REA MCP
  (`evidence_id: ev_fab89ed4cb28b78803740601c388bce12306f462a6e548cdba1763382404d63b`,
  jar `waterframes-NEOFORGE-mc1.21.1-v2.1.23.jar`).
- Código: **All Rights Reserved**.
