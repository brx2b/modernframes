# ModernFrames — Arquitectura

Documento técnico del mod. Para la visión de usuario, ver `README.md`.

## 1. Visión general

```
Cliente A (edita)            Servidor lógico              Clientes (todos)
─────────────────            ───────────────              ────────────────
FrameScreen (GUI)
   │ SetFramePayload (C2S)
   ▼
                         FramePolicy.validate()
                         ImageFrameBlockEntity.setData()
                              │
                              │  sync vanilla de BlockEntity
                              │  (getUpdateTag / ClientboundBlockEntityDataPacket)
                              ▼
                         ┌──────────────────────────────────────────┐
                         │ cada cliente resuelve la fuente por su   │
                         │ cuenta: FrameImageLoader (async) →       │
                         │ FrameTextureManager (DynamicTexture) →   │
                         │ ImageFrameRenderer (quad con UVs)        │
                         └──────────────────────────────────────────┘
```

- **Sin payloads S2C de datos**: la sincronización usa el mecanismo vanilla de block
  entities (`getUpdateTag` para chunk load, `getUpdatePacket` para updates en vivo).
- **Solo 2 payloads propios**: `SetFramePayload` (guardar) y `FrameResultPayload`
  (resultado con mensaje de error/éxito).

## 2. Estructura de paquetes

```
dev.modernframes
├── ModernFrames.java            @Mod: registra registries, red y configs
├── ModernFramesConfig.java      specs COMMON y CLIENT + enum EditPolicy
├── registry/ModRegistry.java    bloque, item, block entity
├── common/
│   ├── data/FrameData.java      record inmutable: NBT + StreamCodec + clamping
│   ├── util/FrameSources.java   rutas sandbox/cache, validación, sha1
│   ├── block/ImageFrameBlock.java          estados FACING+FACE, shapes, uso
│   ├── block/entity/ImageFrameBlockEntity.java  datos+owner, permisos, sync
│   └── network/
│       ├── ModNetwork.java      registro de payloads
│       ├── SetFramePayload.java C2S: valida distancia/política/permiso, aplica
│       ├── FrameResultPayload.java S2C: ok/error (clave de traducción)
│       └── FramePolicy.java     validación compartida (servidor + GUI)
└── client/
    ├── ModernFramesClient.java  renderer del block entity + registro del lector WebP (bus MOD)
    ├── ClientEvents.java        tick de animación + limpieza al desconectar (bus GAME)
    ├── ClientHooks.java         abrir pantalla; mostrar resultado del servidor
    ├── screen/FrameScreen.java  GUI: pestañas URL/Archivo/Tamaño/Marco/Ajustes + preview
    ├── render/ImageFrameRenderer.java  dibuja borde + panel + quad de la imagen
    └── texture/
        ├── FrameImageLoader.java    HTTP + ImageIO/WebP/stb + GIF → NativeImage (off-thread)
        └── FrameTextureManager.java DynamicTexture LRU + animación GIF
```

## 3. Modelo de datos (`FrameData`)

Record con `source`, `kind` (`URL`/`FILE`), `offsetU/V`, `zoom`, `rotation`, `alpha`,
`brightness`, `flipX/Y`, `renderBothSides`, `lit`, `renderDistance`, `sizeW`/`sizeH`
(ancho/alto en bloques), `autoSize` y `backing`/`border` (panel de fondo y borde).

- `clamped()` limita todos los numéricos (usado en servidor **y** cliente).
- `save()/load(CompoundTag)` persisten en disco y viajan en los update tags.
- `STREAM_CODEC` manual (writeUtf con límite de 2048) para el payload de edición.

## 4. Geometría del marco (clave del render)

Convención: **`FACING` es la normal hacia afuera** (dirección a la que mira la imagen).

### 4.1 Modelos (blockstate → y)

El **modelo de bloque está vacío** (`image_frame_empty`): todo lo visible lo dibuja el
BlockEntityRenderer del master (ver 4.2). Los modelos antiguos
(`image_frame{,_floor,_ceiling}`) se conservan solo para el **modelo de ítem**, que sigue
mostrando la placa.

Las tablas de rotación se verificaron contra assets vanilla 1.21.1 extrayendo
`wall_torch.json` del client jar (anclaje empírico de la rotación local de blockstates) y se
mantienen porque el shape/colisión y el modelo del ítem dependen de ellas:

| FACING | y (JSON) | Placa en | Shape (colisión) |
|---|---|---|---|
| north | 0 | borde sur | `z 15.5–16` |
| east | 90 | borde oeste | `x 0–0.5` |
| south | 180 | borde norte | `z 0–0.5` |
| west | 270 | borde este | `x 15.5–16` |

Suelo y techo usan modelos horizontales propios (`image_frame_floor`,
`image_frame_ceiling`); son simétricos, así que el `y` no afecta visualmente.

### 4.2 Borde, panel y quad de la imagen (renderer)

Para evitar cualquier ambigüedad de convención de rotaciones del `PoseStack`, el renderer
**no rota la pose**: calcula la base ortonormal directamente en espacio mundo:

```
n  = normal hacia afuera        (FACING para pared; +Y suelo; -Y techo)
r  = FACING.getCounterClockWise()   ("derecha" del contenido)
up = n × r

placa   = eje n a 1/32 de la pared (PLATE_PLANE, la superficie del antiguo modelo)
imagen  = eje n a 1/32 + 1 mm      (PLANE_OFFSET)
halfR   = sizeW/2 − 1/16,   halfU = sizeH/2 − 1/16
vértice = centro + r·(±halfR) + up·(±halfU)
```

El renderer dibuja siempre (aunque no haya imagen configurada, si no el marco sería
invisible con los modelos vacíos):

1. **Borde**: 4 tiras de 1/16 en el plano placa, UVs del anillo exterior de
   `textures/frame/frame.png` (una repetición por bloque a lo largo).
2. **Panel de fondo**: quad plano en el hueco, UV fijada al texel central oscuro de la
   misma textura; tinte blanco y luz natural. Es lo que oculta la pared (y sus juntas)
   cuando la imagen es translúcida o tiene alfa.
3. **Imagen** (solo si hay fuente): quad con UVs aspect-fit (4.3), 1 mm por delante.

- Motivo del rediseño: con modelos por bloque, **cada** bloque interior repetía el borde de
  la textura y las juntas se veían a través de imágenes translúcidas (rejilla). Ahora hay un
  único borde y un único panel para todo el rectángulo.
- `backing`/`border` permiten ocultar el panel o el borde; los esclavos no dibujan nada.
- Vértices en orden CCW visto desde afuera; con `renderBothSides` se emiten además los quads
  espejados en el plano opuesto (`1 − d`, uvs fijadas a cada esquina).
- La textura del marco es standalone (`textures/frame/`) porque `RenderType.entityTranslucent`
  no puede muestrear el atlas de bloques.

### 4.3 UVs

Ventana UV con **aspect-fit** dentro del cuadrado + zoom/paneo + rotación (sobre las UVs,
no sobre la geometría):

```
halfU = fitW / (2·zoom),  halfV = fitH / (2·zoom)
cu = 0.5 + clamp(offsetU,-1,1)·(0.5−halfU)   (idem cv)
u = clamp(cu + (fx·cos − fy·sin)·halfU, 0, 1)
v = clamp(cv − (fx·sin + fy·cos)·halfV, 0, 1)   con flip aplicado antes
```

`RenderType.entityTranslucent(texture)`; luz `FULL_BRIGHT` si `lit`; color de vértice =
`brightness` (RGB) y `alpha`.

### 4.4 Marcos multibloque (maestro/esclavo)

- `ImageFrameBlockEntity` es **master** (sin `Master` en NBT: guarda `FrameData`) o
  **esclavo** (guarda solo `Master` = BlockPos). Todos los bloques comparten blockstate y el
  master ocupa siempre la esquina mínima del rectángulo.
- `FrameGeometry`: base `right`/`up`, celdas del rectángulo, `findMaster()` y descomposición
  de la cara clicada en un eje de redimensionado.
- `FrameResizer` (servidor): `resizeTo` (GUI), `push` (clic derecho, expandir/reducir en el
  eje de la cara, con `relocateMaster` cuando toca el lado bajo) y `collapse` (romper
  cualquier bloque elimina el resto y cae 1 solo objeto). Valida límites
  (`resize.maxSize/maxArea`) y que las celdas nuevas sean reemplazables.
- Render: `BlockEntityRenderer.getRenderBoundingBox` devuelve el AABB del rectángulo
  completo, para que el culling no corte la imagen cuando el master sale del frustum.
- UV: `panelAspect = (sizeW − 1/8)/(sizeH − 1/8)` sustituye al cuadrado fijo.

## 5. Carga de imágenes

`FrameImageLoader` (hilos daemon "ModernFrames-IO"):

1. **URL** → `java.net.http.HttpClient` con timeout, límite de bytes, caché en disco
   (`config/modernframes/cache/<sha1>.img`).
2. **Archivo** → resuelto contra el sandbox (`config/modernframes/images`, rechazando
   `..`/rutas fuera de la carpeta) o ruta absoluta si la política lo permite.
3. **Decode**: `ImageIO` con el lector WebP de **TwelveMonkeys** registrado explícitamente
   en `FMLClientSetupEvent` (`FrameImageLoader.registerCodecs()`):
   - Formatos del JDK + WebP (con y sin pérdida) → `BufferedImage` → `NativeImage`
     (conversión ARGB→ABGR).
   - GIF multi-frame (solo si `getFormatName() == "gif"` y hay >1 imagen) → compositing
     completo: canvas lógico del descriptor de pantalla, offsets de cada frame, disposal
     *restoreToBackground*/*restoreToPrevious*, delays (clamp 20–10000 ms) y muestreo si
     supera `gifs.maxFrames`. Los WebP animados se muestran como primer fotograma.
   - Fallback `NativeImage.read` (stb_image del propio juego) cuando ImageIO no reconoce el
     archivo: añade TGA, PSD, PNM y HDR.
   - Escalado bilineal a `maxTextureSize` (512 para GIFs).
4. El callback vuelve al hilo de render con `Minecraft.execute`.

**Dependencia embebida**: los 6 jars de TwelveMonkeys 3.15.3 (BSD 3-Clause) se empaquetan con
JarJar en `META-INF/jarjar/` y FML los carga al arrancar; el registro explícito del SPI evita
depender del descubrimiento de servicios dentro del class loader del juego.

`FrameTextureManager`:

- Una `DynamicTexture` por fuente (clave `KIND|source`), compartida entre todos los marcos
  con la misma imagen.
- LRU con `maxCachedImages`; al evictar: `TextureManager.release` + `NativeImage.close`.
- `tick()` (ClientTickEvent.Post) avanza la animación subiendo el frame actual con
  `texture.bind() + NativeImage.upload(...)`.
- `clearAll()` al `ClientPlayerNetworkEvent.LoggingOut`.

## 6. Red y permisos

`SetFramePayload` (manejado en `enqueueWork`, servidor):

1. Distancia jugador↔marco ≤ 64 bloques (anti-cheat).
2. `FramePolicy.validate(data)` → rechaza según config (URLs remotas, locales, absolutas,
   longitud, esquema).
3. `ImageFrameBlockEntity.canEdit(player)`: op → sí; si no, según `EditPolicy`
   (`EVERYONE`/`CREATIVE_ONLY`/`OWNER`).
4. Si cambia `sizeW/sizeH`: `FrameResizer.resizeTo` (clamp a límites + validación de celdas
   + remodelado + sync del rectángulo).
5. `setData(data, player)` guarda, fija owner, `setChanged()` + `sendBlockUpdated` (sync
   vanilla a todos los clientes).
6. Responde `FrameResultPayload`; el cliente muestra el mensaje (action bar) y, si hay
   pantalla abierta, el error inline.

`ResizeFramePayload` (clic derecho con el marco en la mano): `FrameResizer.push` aplica ±1
bloque en el eje de la cara clicada (o reduce con agachado) y solo responde en caso de error.

La misma `FramePolicy.validate` se ejecuta en el cliente al pulsar *Vista previa* para dar
feedback inmediato (con la copia local de la config COMMON).

## 7. Recursos (formato 1.21.1)

```
assets/modernframes/blockstates/image_frame.json   (variante única "" → modelo vacío)
assets/modernframes/models/block/image_frame_empty.json  (sin elements: todo lo dibuja el BER)
assets/modernframes/models/block/image_frame{,_floor,_ceiling}.json  (solo para el ítem)
assets/modernframes/models/item/image_frame.json
assets/modernframes/textures/block/image_frame.png  (16×16, generada por script)
assets/modernframes/textures/frame/frame.png        (copia standalone para el BER)
assets/modernframes/lang/{en_us,es_es}.json
data/modernframes/loot_table/blocks/image_frame.json   (singular en 1.21+)
data/modernframes/recipe/image_frame.json              (result {"id","count"})
data/minecraft/tags/block/mineable/axe.json
META-INF/jarjar/*.jar + metadata.json                  (TwelveMonkeys 3.15.3 vía JarJar)
META-INF/THIRD-PARTY-NOTICES.txt
```

## 8. Referencias de comportamiento

- **WATERFrAMES** (1.21.1-neo, commit `ac7a4e2e`): modelo de datos DisplayData/DisplayTile,
  convención de rotaciones de pared, uso de DynamicTexture. Analizado también como
  artefacto con REA MCP (`ev_fab89ed4cb28b78803740601c388bce12306f462a6e548cdba1763382404d63b`).
- **LittleFrames** (1.21, `f0060437`): UI y ajustes de marcos.
- Assets vanilla 1.21.1 (`wall_torch`, `lever`) extraídos del client jar de la instancia
  Prism para anclar empíricamente las convenciones de rotación.

No se copió código de ninguno de los dos mods.

## 9. Pendientes conocidos

- Ampliar/reducir no consume ítems (decisión de diseño; revisar si desequilibra).
- Los comandos (`/setblock`, `/fill`) pueden dejar esclavos huérfanos; todos los caminos de
  jugador (romper, explosiones, resize) están cubiertos.
- El lienzo GIF usa el "logical screen" del stream; GIFs raros (frames de distinto tamaño
  sin descriptor coherente) pueden requerir fallback a frame 0.
- Los WebP animados muestran solo el primer fotograma (el compositing GIF es específico de
  GIF; extenderlo sería un extra).
- Considerar paquetes S2C de compresión si los update tags crecen.
