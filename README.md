# Cloudy Scanner

App Android per scansionare l'ambiente in 3D con la fotocamera: nuvola di punti in tempo reale,
ricostruzione mesh low/mid/high poly, strumenti di visualizzazione 3D e un assistente IA che gira
sul telefono.

Pensata per **Google Pixel 7 Pro con Android 17** (`targetSdk 37`, `minSdk 31`, solo `arm64-v8a`).

## Installazione

Ogni push fa partire la build su GitHub Actions. L'APK firmato è:

- nel branch `ci-out` (`CloudyScanner.apk`), oppure
- tra gli artifact dell'ultima esecuzione di **Actions → Build APK**.

Serve *Google Play Services per AR* (ARCore): se manca, l'app propone di installarlo.

## Come funziona

| Funzione | Come è fatta |
|---|---|
| Nuvola di punti | Mappe di profondità grezze di ARCore + posa della camera, fuse in una griglia voxel sparsa da 1,5 cm (un punto mediato per voxel, con normale, colore e affidabilità). |
| Zone incerte | I punti visti poco o di sfuggita restano con affidabilità bassa: l'app li evidenzia in arancione e indica con un anello/freccia dove ripassare. |
| Soggetto | Tocca lo schermo per agganciarlo: viene segmentato per continuità di profondità e seguito fotogramma per fotogramma. Se si muove, i punti vecchi vengono rimossi (free-space carving) e riacquisiti. |
| Distanze | Profondità ARCore del soggetto e del centro inquadratura, più la distanza di fuoco dell'obiettivo (autofocus laser + PDAF). |
| Fotocamere | Elenco di tutti i moduli via Camera2: focale, equivalente 35 mm, apertura, sensore, fuoco minimo, OIS. |
| Mesh | Campo di distanza con segno dai punti orientati + Surface Nets: quads nativi, divisi in triangoli a richiesta. Low 8 cm, Mid 4 cm, High 2 cm. Conteggio di vertici, quads e triangoli. |
| Vista | AR o orbita libera; fotocamera, contorni, punti, mesh solida, wireframe quads/triangoli, backface culling, luci, flat shading, colori, x-ray, griglia, bounding box, assi, ortografica, rotazione automatica, onda, pulsazione. |
| Cancellazione | Passa il dito: i punti diventano viola, poi *Elimina*. C'è l'annulla. |
| IA locale | Gemma 4 (`.litertlm`) con LiteRT-LM. Il file resta in `Download`, quindi sopravvive alla disinstallazione e viene ritrovato da solo. |
| Aula virtuale | L'IA (o le regole apprese, se non c'è un modello) ripulisce e ricostruisce la scena passo passo, spiegando cosa fa. |
| Memoria | `Download/CloudyScanner/knowledge.json`: note brevi scritte dall'IA, scelte abituali e tempi di fusione. Cresce fino a 48 note e viene riusata nei prompt e nei valori predefiniti. |

## Lavorare su un oggetto (menu "Oggetto ▾")

Tocca l'oggetto nella vista AR per selezionarlo. Da quel momento:

| Funzione | Come è fatta |
|---|---|
| Priorità all'oggetto | Vengono fusi solo i pixel di profondità dell'oggetto; il resto dell'inquadratura è ignorato. |
| Elimina il superfluo | Tutti i punti mai osservati come parte dell'oggetto diventano viola e, alla conferma, vengono cancellati (con annulla). |
| Foto automatiche | Lo spazio attorno all'oggetto è diviso in viste (davanti, dietro, lati, sopra…). Ogni vista tiene solo la foto migliore; quelle mosse (velocità di rotazione × tempo di posa), poco nitide (varianza del Laplaciano) o doppie sono scartate. Le foto stanno nella cache dell'app con posa e parametri ottici. |
| Quante viste | Semplice 3, Media 5, Complessa 13. Lo decide la forma (distribuzione delle normali) o l'IA quando riconosce l'oggetto; si può fissare a mano. |
| Distanza e prospettiva | L'app calcola la distanza ideale dall'ingombro dell'oggetto e dal campo visivo, e dice dove spostarsi per la vista che manca. Il cerchio in basso a destra mostra le viste coperte. |
| Ricolora dalle foto | Ogni punto prende il colore dalla foto che lo vede più di fronte e da più vicino. |
| 256 colori | Palette a 256 colori (median cut) calcolata sull'oggetto e applicata solo a lui. |
| Analizza l'oggetto | Ingombro in cm, punti, forma, colori dominanti, viste mancanti; con il modello IA anche nome, materiali e consigli, da un mosaico delle foto migliori. |
| IA live | Ogni pochi secondi il modello guarda l'inquadratura (ritagliata sull'oggetto), dice cosa vede e, se non è sicuro, chiede cosa mostrargli. |

## Modello pulito: pochi solidi esatti invece di una pelle densa

Con **Modello pulito** attivo (Opzioni → Ricostruzione; è il comportamento predefinito di Low/Mid/High) la scansione
viene ricostruita come la modellerebbe una persona in Blender:

- tutto ciò che è tondo attorno a un asse (lattina, bottiglia, tavolo rotondo con la sua gamba) diventa **un solido di
  rotazione**: il raggio è misurato lungo l'asse, ridotto a tratti dritti, coni e gradini, e ruotato con un cerchio a N lati
  (6, 8, 12, 16, 24, 32 o automatico in base a livello e dimensione). Le estremità piatte sono singoli N-goni;
- le facce rettangolari diventano **lastre** con lo spessore visto sui bordi (un piano del tavolo = 8 vertici);
- tubi e curve restano tubi e curve;
- **allineamenti**: assi e facce entro 7° da verticale, orizzontale, parallelo o perpendicolare vengono resi esatti;
  spessori quasi uguali (le gambe di un tavolo) diventano uguali; le estremità che arrivano a pochi centimetri da una faccia
  ci si appoggiano esattamente;
- **smussi** opzionali sui bordi tondi; se l'IA riconosce una lattina, aggiunge bordo e coperchio incassato tipici
  (dichiarati come non misurati);
- il resto può restare come mesh normale ("Tieni i dettagli irregolari"), così non si perde nulla.

L'OBJ esportato ha un oggetto con nome per ogni solido, quads e N-goni, ombreggiatura piatta e un materiale per oggetto nel suo colore dominante (file .mtl accanto): si importa in Blender così com'è.

### Oggetti separati e catalogo (v1.4)

- La scena viene divisa in **oggetti**: un tavolino diventa piano, gambe divergenti e coniche, tovaglietta (disco sottile),
  piatto e candela, ognuno col suo nome. Le pile di oggetti tondi (candela su piatto su tovaglietta) vengono tagliate dove
  cambiano raggio, colore o spessore. Le forme verticali non tonde (una barra a V su una base) diventano **prismi estrusi**
  dal loro contorno; un oggetto tornito in due colori (una clessidra) resta un solo oggetto con due fasce di colore.
- Ogni oggetto prende il **colore dominante** delle sue superfici. LOW = pochi lati e profili dritti; HIGH = più lati e
  profili curvi e smussati.
- **Mesh ▾ → Catalogo oggetti**: nome, misure, colore, quante foto lo inquadrano e da quanti lati. Si può rinominare un
  oggetto o farlo **riconoscere all'IA**: ritaglia l'oggetto dalla foto migliore e chiede al modello che cos'è. Il nome
  cambia la forma: *piatto*, *ciotola*, *barattolo*, *vaso* diventano cavi; *candela* ha l'incavo dello stoppino;
  *lattina* bordo e coperchio. L'IA live dà il nome all'oggetto selezionato da sola. Il catalogo si esporta in CSV.
- **Foto automatiche anche senza oggetto selezionato**: il pianificatore inquadra tutta la scena e chiede le viste che
  mancano, scartando quelle mosse o doppie.

## Forme regolari in NURBS (Mesh ▾ → Ricostruisci NURBS)

La nuvola (dell'oggetto selezionato, o tutta) viene cercata per forme regolari, che diventano superfici NURBS esatte:

- **tubi** a sezione circolare e asse rettilineo, divisi dove cambia lo spessore (parte più grossa / più sottile);
- **curve** di raccordo tra due tubi (toro esatto; l'angolo e il raggio di curvatura sono ricavati dai punti);
- **dischi** (cerchio NURBS) e **piani rettangolari**.

Il rilevamento è un RANSAC su punti orientati, rifinito ai minimi quadrati. L'esportazione **IGES** (entità 128) si apre in
FreeCAD, Rhino, Fusion; il file è stato verificato con OpenCascade. Le forme libere restano come nuvola/mesh.

## Ricostruzione da foto importate (Foto → 3D)

Bastano 3 foto: con poche foto ogni coppia viene provata per scegliere la partenza migliore, e a posizioni note una seconda
passata aggiunge i dettagli più incerti. Alla fine viene costruito un modello e, se il modello IA è caricato, le foto vengono
analizzate per dire che oggetto è e quali scatti mancano.

Scegli più foto dello stesso soggetto: l'app trova gli stessi dettagli nelle varie foto (SIFT, OpenCV), calcola da dove è
stata scattata ciascuna e triangola una nuvola di punti colorata che cresce foto dopo foto. Poi si usa come una scansione:
mesh, NURBS, cancellazione, esportazione.

## Limiti da conoscere

- Il Pixel 7 Pro non ha un sensore di profondità (ToF/LiDAR) accessibile alle app: la profondità è
  stimata da ARCore dal movimento. La precisione tipica è di qualche centimetro a 1–2 m.
- Il laser del telefono serve all'autofocus; Android espone solo la distanza di fuoco risultante.
- La "memoria" dell'IA è un taccuino riusato nei prompt, non un riaddestramento del modello.
- "IA live" non è video in tempo reale: è uno sguardo ogni pochi secondi, quanto il telefono riesce a far girare il modello.
- Le foto migliorano colore, riconoscimento ed esportazione (con pose, per fotogrammetria esterna); la geometria resta quella
  della profondità ARCore.
- Nel ricolorare, l'occlusione è stimata solo dall'orientamento della superficie.
- NURBS: con il rumore della profondità ARCore servono tubi di almeno 4–5 cm di diametro; oggetti più sottili non vengono riconosciuti.
- Foto → 3D produce una nuvola rada (migliaia di punti sui dettagli ben visibili), senza scala reale e senza la rifinitura
  globale dei programmi di fotogrammetria da computer. Superfici lisce o lucide restano vuote.

## Sviluppo

```
./gradlew testDebugUnitTest assembleRelease
```

I test (`app/src/test`) simulano una camera di profondità che orbita attorno a una scena sintetica
e verificano fusione, tracking del soggetto, guida e mesh.
