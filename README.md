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

## Limiti da conoscere

- Il Pixel 7 Pro non ha un sensore di profondità (ToF/LiDAR) accessibile alle app: la profondità è
  stimata da ARCore dal movimento. La precisione tipica è di qualche centimetro a 1–2 m.
- Il laser del telefono serve all'autofocus; Android espone solo la distanza di fuoco risultante.
- La "memoria" dell'IA è un taccuino riusato nei prompt, non un riaddestramento del modello.
- "IA live" non è video in tempo reale: è uno sguardo ogni pochi secondi, quanto il telefono riesce a far girare il modello.
- Le foto migliorano colore, riconoscimento ed esportazione (con pose, per fotogrammetria esterna); la geometria resta quella
  della profondità ARCore.
- Nel ricolorare, l'occlusione è stimata solo dall'orientamento della superficie.

## Sviluppo

```
./gradlew testDebugUnitTest assembleRelease
```

I test (`app/src/test`) simulano una camera di profondità che orbita attorno a una scena sintetica
e verificano fusione, tracking del soggetto, guida e mesh.
