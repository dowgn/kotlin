# Kotlin-X : audit systémique de Kotlin et vision d'un langage natif pour l'IA, les agents et l'IoT

> **Avertissement.** Ce document est un exercice de recherche prospective —
> une autopsie architecturale de Kotlin suivie de la conception spéculative
> d'un langage successeur, "Kotlin-X". Il ne constitue ni une roadmap
> officielle de JetBrains, ni un engagement d'implémentation. Il est écrit
> avec la rigueur d'un rapport d'ingénierie et cite, chaque fois que
> possible, des éléments réels de l'architecture du compilateur présents
> dans ce dépôt, plutôt que des généralités marketing sur le langage.

---

## Partie I — Diagnostic systémique de Kotlin

### 0. Positionnement comparatif

Avant d'entrer dans le détail architectural, il est utile de situer Kotlin
par rapport aux langages qui incarnent le mieux, chacun, une des exigences
que Kotlin-X doit satisfaire simultanément : la rigueur de typage de Rust,
la simplicité de concurrence de Go, la sûreté d'interop native de Swift.

| Dimension                          | Kotlin (actuel)                          | Rust                              | Go                                 | Swift                              |
|-------------------------------------|-------------------------------------------|------------------------------------|--------------------------------------|--------------------------------------|
| Gestion mémoire                     | GC (JVM) / GC multi-implémentations (Native, encore WIP) | Ownership/borrow-checker, sans GC | GC concurrent unique, stabilisé    | ARC (comptage de référence)         |
| Types somme natifs                  | Absents (simulés par `sealed`)            | `enum` avec variantes de données   | Absents (interfaces + type switch) | `enum` avec valeurs associées       |
| Concurrence par défaut              | Coroutines + structured concurrency       | `async`/`await` + ownership        | Goroutines + channels (sans arbre)  | `async`/`await` + acteurs (Swift 6) |
| Monde de compilation                | Ouvert (JVM/JS) / fermé (Native)          | Fermé (crate-based)                | Fermé (package-based)              | Fermé (module-based)                |
| Cible bare-metal/microcontrôleur    | Non prioritaire (Native vise iOS/serveur) | Cible de premier ordre             | Non prioritaire                    | Non prioritaire                    |
| Dette d'interop historique          | Java/JVM (platform types, érasure)        | Aucune (langage jeune, pas d'héritage) | Aucune                          | Objective-C (pont ARC/MRC)          |

Ce tableau n'est pas une évaluation de supériorité globale — Kotlin reste,
pour le développement d'applications JVM/Android, le langage le mieux
outillé de sa catégorie. Il sert à isoler précisément les dimensions sur
lesquelles Kotlin-X doit converger vers Rust (mémoire, bare-metal) et vers
Go (simplicité de concurrence à grande échelle) sans sacrifier ce que
Kotlin a déjà résolu mieux que ces deux langages (expressivité du système
de types, interop JVM, DSL).

### 1. Système de types et syntaxe

Le système de types de Kotlin est, dans l'absolu, l'un des plus aboutis du
monde JVM : null-safety au niveau du type, variance déclarée en site de
définition (`out`/`in`), types réifiés via `inline fun <reified T>`, smart
casts contrôlés par flux, et depuis peu un système de contrats
(`contract { }`) permettant au frontend d'exploiter des invariants
déclarés par la bibliothèque standard. Mais un audit honnête doit dépasser
la promesse marketing de la "null safety" et regarder les limites
structurelles.

**Absence de types somme natifs (union/intersection types).** Kotlin n'a
pas de véritable type union discriminé au niveau du système de types — on
simule ce besoin avec des hiérarchies `sealed class`/`sealed interface`,
qui exigent une déclaration nominale a priori de chaque variante. Cette
absence n'est pas cosmétique : elle a un impact architectural direct et
documenté dans le compilateur lui-même. L'implémentation des fonctions
`suspend` doit représenter la valeur de retour d'un point de suspension
comme *soit* `COROUTINE_SUSPENDED` *soit* la valeur réelle de type `T` — un
cas d'école de type union `T | COROUTINE_SUSPENDED`. En l'absence d'un tel
type, le compilateur érase purement et simplement le type de retour à
`Any?` dans l'`invokeSuspend` généré (`compiler/backend/.../coroutines-codegen.md`).
Le prix est payé au runtime : les erreurs de typage qui devraient être
statiques (par ex. sur `suspendCoroutineUninterceptedOrReturn`, dont le
document interne reconnaît explicitement que le type de retour n'est *pas*
vérifié) deviennent des `ClassCastException` en production. Un besoin
récurrent du langage (représenter un résultat *ou* un signal de contrôle)
est donc satisfait par une échappatoire à l'érasure plutôt que par le
système de types — c'est une dette de conception, pas un détail
d'implémentation.

**Variance et sites d'utilisation : un compromis non résolu.** Kotlin offre
à la fois la variance déclarée (`class Producer<out T>`) et la variance en
site d'utilisation (`List<out T>` à l'appel), ce qui double la charge
cognitive du développeur qui doit connaître les deux mécanismes et savoir
quand chacun s'applique — contrairement à Scala (déclarée uniquement dans
la pratique courante) ou à Java (site uniquement, obligatoire). Le
raisonnement "pourquoi ce `<out T>` compile ici et pas là" reste une des
sources de confusion les plus citées par les développeurs intermédiaires,
révélant une expressivité qui n'a pas été suivie d'une simplification de
l'expérience.

**Platform types : une fuite délibérée du système de types.** L'interop
Java introduit une troisième nullabilité — ni `T` ni `T?` mais `T!`
("platform type") — pour tout type provenant du bytecode Java sans
annotation de nullabilité. Ce type n'est *pas exprimable* en syntaxe
Kotlin : il n'existe que dans l'inférence du compilateur et disparaît dès
que le développeur choisit une annotation explicite. La documentation
interne du module PSI (`compiler/psi/AGENTS.md`, domaine qui vit
quotidiennement cette tension en migrant du code Java vers Kotlin) illustre
le problème de façon très concrète : convertir une méthode comme
`PsiElement.getParent()` en Kotlin oblige à choisir entre `PsiElement` et
`PsiElement?`, et les deux choix sont des ruptures de compatibilité
binaire pour les appelants existants. La pratique documentée consiste à
*garder le type de retour implicite* pour laisser le compilateur émettre un
platform type — une esquive du système de types by design, entérinée comme
solution acceptable pour ne pas casser l'écosystème.

**Verbosité cachée.** Le DSL builder pattern (`buildString { }`,
`remember { }`, DSL Gradle/Compose) déplace la complexité syntaxique dans
les signatures de fonctions à récepteur étendu (`fun <T> T.apply(block: T.() -> Unit)`),
ce qui rend l'inférence de type et les messages d'erreur nettement plus
opaques que la syntaxe apparente ne le suggère — le "code court" cache un
graphe de résolution de surcharge et d'inférence de type par contrainte qui
peut devenir illisible pour l'IDE lui-même sur des DSL profondément
imbriqués. Les délégués de propriété (`by lazy`, `by Delegates.observable`)
répètent le même phénomène : une ligne de déclaration masque un appel
`getValue`/`setValue` entier, invisible dans la trace d'appel sans support
IDE.

**Extensions et multi-dispatch simulé.** Les fonctions d'extension
permettent d'ajouter du comportement à un type sans en modifier la
déclaration, mais la résolution reste statique (liée au type déclaré de
la variable réceptrice, pas à son type dynamique). Ce choix, cohérent avec
le reste du système de types statique de Kotlin, produit néanmoins une
classe entière de bugs silencieux propres au langage : une extension
`fun Animal.speak()` et une extension plus spécifique `fun Dog.speak()`
ne se comportent *pas* comme une redéfinition polymorphique si la
variable est déclarée `Animal` même si elle contient un `Dog` — le
compilateur choisit l'extension au type statique, sans avertissement
visible au site d'appel. C'est un piège d'expressivité qui n'existe pas
dans les langages qui n'offrent pas d'extension functions comme sucre
syntaxique de premier ordre.

### 2. Modèle de concurrence : Coroutines et Flow face aux architectures hautement scalables

Les coroutines Kotlin restent une des propositions de concurrence les plus
ergonomiques du paysage JVM — code séquentiel en apparence, machine à
états en réalité, `structured concurrency` comme discipline par défaut.
Mais un examen architectural révèle plusieurs zones de friction réelles.

**Dette d'implémentation entre backends.** La documentation interne des
coroutines (3177 lignes dans `coroutines-codegen.md`) révèle que
JVM_IR — la génération de code IR-first — dépend *encore* de l'ancien
pipeline de transformation bytecode (`CoroutineTransformerMethodVisitor`,
`getOrCreateJvmSuspendFunctionView`) pour gérer l'inlining de fonctions
`suspend` en monde ouvert (bibliothèques externes non recompilées
ensemble). À l'inverse, les backends JS et Native n'émettent *aucun*
marker de suspension et construisent la state machine directement pendant
le lowering, car ils opèrent en monde fermé. Résultat : le comportement et
même la performance des coroutines *diffèrent structurellement* selon la
cible de compilation, ce qui contredit partiellement la promesse "un seul
modèle de concurrence, plusieurs plateformes" du multiplateforme.

**Contexte implicite comme dynamic scoping déguisé.** `CoroutineContext`
se propage implicitement le long de la chaîne d'appel `suspend`, un
mécanisme qui reproduit — sans le dire — un dynamic scoping façon Lisp
(comparable aux "implicits" Scala ou au `ThreadLocal`). Ce choix
d'ergonomie a un coût de traçabilité : déterminer statiquement *quel*
`Dispatcher`, quel `Job` parent ou quel `CoroutineExceptionHandler` sera
actif à un point d'appel donné exige de remonter tout l'arbre d'appel
dynamique, ce qu'aucun outil d'analyse statique du dépôt ne résout
pleinement aujourd'hui (l'Analysis API elle-même documente la difficulté
de l'analyse "lazy" incrémentale pour ce genre de propagation contextuelle
dans `analysis/AGENTS.md`).

**Annulation coopérative, un contrat social plutôt qu'une garantie.**
L'annulation d'une coroutine repose sur la vérification volontaire de
`isActive`/`ensureActive()` aux points de suspension. Une fonction `suspend`
qui exécute une boucle CPU-bound sans point de suspension ignore
silencieusement l'annulation — ce n'est pas un bug, c'est un choix de
conception documenté, mais il rend le modèle fondamentalement différent
des primitives préemptives d'un `Thread.interrupt()` fiable ou d'un
`context.Done()` de Go, avec une surface d'erreur que seule la discipline
du développeur referme.

**Flow face aux architectures réactives à très grande échelle.** `Flow`
choisit un modèle "cold" par défaut avec backpressure structurel (suspension
du producteur), ce qui est plus sûr que RxJava sur le papier, mais impose
au développeur de connaître un vocabulaire parallèle entier
(`StateFlow`/`SharedFlow`/`callbackFlow`/`channelFlow`) pour couvrir les cas
hot/cold/replay que Reactive Streams unifie dans un modèle unique de
`Publisher`. Sur des architectures à très haut débit (event sourcing,
streaming multi-agents), cette fragmentation de l'API impose des choix
architecturaux précoces et rarement réversibles sans réécriture.

**Le prix caché du multi-threading structuré à très grande échelle.** Le
modèle de structured concurrency impose qu'un scope parent attende la
terminaison de tous ses enfants, ce qui est excellent pour la correction
locale (pas de coroutine "orpheline") mais devient un goulot d'étranglement
architectural dans les systèmes qui doivent gérer des dizaines de milliers
de flux concurrents de courte durée (passerelles IoT, brokers de messages
multi-agents) : chaque `Job` enfant porte un coût de comptabilité (liste
chaînée de parenté, propagation d'annulation) qui n'est pas gratuit à
grande échelle, contrairement aux green threads sans hiérarchie explicite
de Go (`goroutine` + `channel`, sans arbre de supervision obligatoire) ou
aux processus légers d'Erlang/BEAM, dont le "let it crash" repose sur un
superviseur explicite plutôt que sur une annulation en cascade implicite
dans l'arbre d'appel.

### 3. Écosystème et compilation : KMP, Native, Wasm, GC, K2

**Kotlin Multiplatform : `expect`/`actual` comme métaprogrammation
pauvre.** Le mécanisme `expect`/`actual` reste, structurellement, une
vérification de correspondance nominale entre déclarations — pas une
véritable abstraction de plateforme au niveau du système de types. Il
n'existe pas de mécanisme de contrainte ("cette actual doit satisfaire ces
capacités") au-delà de la signature elle-même, ce qui pousse à des
patterns de contournement (interfaces communes + délégation) dès que la
divergence entre plateformes dépasse une simple substitution 1:1.

**Kotlin/Native : un GC toujours "work in progress".** Le nouveau
gestionnaire mémoire de Kotlin/Native n'est pas un collecteur unique
stabilisé mais un ensemble de quatre implémentations sélectionnables
(`cms`, `pmcs`, `stms`, `noop`, sous `kotlin-native/runtime/src/gc/`), avec
un scheduler de déclenchement lui-même pluggable
(`adaptive`/`aggressive`/`manual`). L'allocateur "custom" — page-based, avec
classes de taille dédiées (`FixedBlockPage`, `NextFitPage`,
`SingleObjectPage`) — est explicitement documenté comme "design still work
in progress" dans son propre README. Ce n'est pas un allocateur de type
mimalloc éprouvé en production depuis une décennie : c'est une pièce de
runtime encore en évolution active, ce qui explique en grande partie
l'écart de maturité perçu entre Kotlin/Native et des runtimes comme celui
de Go ou de Rust (`Box`/ownership sans GC du tout).

**Modèle de compilation "monde fermé" comme frein structurel à la
modularité.** `docs/native/compilation-model.md` documente que les klibs
Kotlin/Native ne sont traduites en code machine qu'à l'étape finale de
link — il n'existe pas de partage de code machine cross-binaire comme le
permettrait un vrai modèle de compilation séparée à la C/Rust. Le corollaire
documenté est le piège des "frameworks ombrelles" en interop Swift/ObjC :
si deux frameworks Kotlin/Native partagent une dépendance commune, celle-ci
peut être dupliquée sous deux identités de type incompatibles côté Swift.
C'est un problème d'architecture de linkage, pas un bug ponctuel.

**Kotlin/Wasm : maturité documentaire quasi nulle.** À la différence des
backends JVM et Native, dont l'architecture est documentée en profondeur
(`compiler/AGENTS.md`, `docs/native/`), le backend Wasm se résume dans le
dépôt à un fichier `ReadMe.md` d'une seule phrase
(`compiler/ir/backend.wasm/ReadMe.md`). Ce n'est pas une preuve d'absence
de travail technique, mais c'est un signal fiable de l'écart de priorité
et de maturité entre les cibles de compilation de Kotlin — Wasm reste, en
2026, la cible la moins outillée pour un contributeur externe cherchant à
comprendre l'architecture avant de modifier le code.

**K2/FIR : résolution séquentielle des phases comme goulot
d'incrémentalité.** Le frontend K2 impose un invariant fort : pour deux
phases séquentielles A→B, tout élément FIR visible en phase B est déjà
résolu à la phase A (`FirResolvePhase.kt`, cité dans
`compiler/AGENTS.md`). Cette discipline de phase est ce qui rend K2 plus
rapide et plus prévisible que K1 en compilation "batch" — mais elle impose
aussi une dépendance d'ordre stricte qui complique structurellement
l'analyse *vraiment* incrémentale et à grain fin exigée par l'IDE. C'est
précisément pour contourner cette contrainte que l'Analysis API a dû
construire une couche séparée entière — `low-level-api-fir` — dédiée à
l'analyse paresseuse, plutôt que de réutiliser directement le pipeline de
compilation batch. Autrement dit : le compilateur de production et
l'analyseur IDE ne partagent pas un seul moteur de résolution incrémentale
natif, ils en ont deux, avec le coût de synchronisation que cela implique.

### 4. Empreinte de l'outillage : le coût de la double vérité IDE/compilateur

Le choix architectural documenté en I.3 — deux moteurs de résolution FIR
distincts, l'un pour la compilation batch, l'autre (`low-level-api-fir`)
pour l'analyse paresseuse de l'IDE — a une conséquence rarement discutée
publiquement : chaque évolution du frontend K2 doit être portée deux fois,
avec deux suites de tests distinctes, et toute divergence de comportement
entre les deux devient un bug utilisateur de type "l'IDE dit une chose, le
compilateur en fait une autre" (diagnostics manquants, quick-fixes
incorrects). L'existence même des `symbol-light-classes`
(`analysis/AGENTS.md`) — une couche de pont entre déclarations Kotlin et
vue Java PSI, nécessaire pour que le reste de l'écosystème IntelliJ
continue de fonctionner sur du code Kotlin — est un signe supplémentaire
que l'outillage Kotlin porte, structurellement, le poids de deux
représentations parallèles du même programme (PSI/Java-compatible et
FIR/Kotlin-natif) qui doivent rester synchronisées à la main à chaque
changement de langage.

### 5. Dette technique : les compromis Java historiques

L'interopérabilité Java à 100 % a été la condition d'adoption de Kotlin —
et elle reste, quinze ans plus tard, la principale force de gravité qui
freine l'évolution "pure" du langage :

- **Platform types** (section 1) : un troisième état de nullabilité qui ne
  peut être exprimé nulle part dans la syntaxe Kotlin elle-même, préservé
  uniquement pour ne pas forcer un choix binaire risqué à chaque appel de
  méthode Java.
- **`@JvmName` indisponible sur les membres d'interface** empêche
  certaines résolutions de collision de nom lors de la conversion
  Java→Kotlin sans rupture de compatibilité binaire — documenté comme
  contrainte de premier ordre dans `compiler/psi/AGENTS.md`, au point que
  toute conversion doit être validée par les mainteneurs PSI avant d'être
  entreprise.
- **Érasure de type partagée avec la JVM** interdit la réification
  générique par défaut (`inline reified` est un pansement local, pas une
  solution générale), contrainte structurelle absente des langages qui ne
  visent pas la compatibilité binaire avec un bytecode existant.
- **Absence de types somme natifs** (section 1) découle en partie du choix
  de ne pas introduire de représentation runtime incompatible avec les
  outils Java existants (débogueurs, sérialiseurs, réflexion).

Le constat : chaque choix de pureté du système de types que l'on pourrait
souhaiter pour Kotlin se heurte, presque systématiquement, à une
contrainte d'interop Java déjà figée dans l'écosystème. C'est un compromis
qui a fait le succès commercial du langage — et qui borne aujourd'hui son
espace de conception.

### 6. Verdict : ce qui doit être abandonné, pas modernisé

Un audit qui se contente de proposer des correctifs incrémentaux à chaque
faiblesse identifiée manque l'essentiel : certains choix ne sont pas des
défauts à corriger, ce sont des vestiges d'une époque de contraintes —
JVM des années 2000, absence d'accélérateurs matériels grand public,
Internet des objets encore inexistant — qui n'ont plus de raison d'être au
centre d'un langage conçu pour l'horizon 2030+. Kotlin-X ne les modernise
pas : il les abandonne.

- **Les platform types et l'érasure de type héritée de la JVM.** Maintenus
  aujourd'hui pour préserver une compatibilité binaire avec du bytecode
  écrit il y a vingt ans. Un langage qui fait de l'IA, des agents et de
  l'IoT des citoyens de première classe n'a plus à porter ce compromis
  dans son cœur : l'interop Java devient une couche de pont optionnelle,
  isolée, jamais une contrainte structurelle du système de types.
- **Le zoo des quatre collecteurs de Kotlin/Native**
  (`cms`/`pmcs`/`stms`/`noop`). Une diversité qui témoigne d'un runtime
  encore en recherche, pas d'un choix d'architecture assumé. Kotlin-X n'en
  garde aucun tel quel.
- **`expect`/`actual`** comme correspondance nominale sans garantie de
  capacité : remplacé par un système de capacités vérifiées, pas réparé
  à la marge.
- **La dépendance structurelle à Gradle** pour tout projet non trivial.
- **Le frontend K1** et la double maintenance qu'il impose encore
  aujourd'hui à K2.
- **Le modèle de compilation "monde fermé" de Native** comme plafond
  définitif de ce qu'un langage moderne doit accepter pour le partage de
  code entre binaires.

Ces éléments ne figurent pas dans le plan de correctifs de la Partie II
ci-dessous : ils sont retirés du socle du langage, et, quand c'est
nécessaire pour l'écosystème existant, relégués à une couche de
compatibilité externe, explicite et ouvertement temporaire — jamais au
cœur de la conception de Kotlin-X.

---

## Partie II — Plan de modernisation et d'amélioration fondamentale

Cette partie ne cherche pas un équilibre prudent entre continuité et
rupture. Elle part du principe inverse, posé en I.6 : chaque mécanisme
identifié comme un vestige — pas une simple faiblesse — est remplacé, pas
rafistolé. L'objectif n'est pas la parité ponctuelle avec Rust ou Go sur
tel ou tel benchmark, c'est un socle technique qui ne doit plus rien aux
contraintes de la JVM de 2011 et qui assume, dès la conception, le
matériel et les charges de travail de la décennie à venir : accélérateurs
IA hétérogènes, essaims d'agents, flottes de capteurs à la périphérie du
réseau.

### 1. Évolution de la syntaxe

- **Types union discriminés natifs** (`Result = Success | Failure`) avec
  vérification d'exhaustivité par le compilateur, remplaçant à la fois les
  hiérarchies `sealed` pour les cas simples et l'érasure `Any?` interne des
  coroutines (section I.1). Bénéfice direct et mesurable : le retour des
  fonctions `suspend` pourrait redevenir un type précis vérifié
  statiquement plutôt qu'une convention interne au bytecode.
- **Contracts de premier ordre, pas de bibliothèque.** Étendre le mécanisme
  `contract { }` actuel (aujourd'hui limité et peu exposé) vers une syntaxe
  de préconditions/postconditions vérifiables statiquement dans les cas
  simples (non-nullité conditionnelle, invariants de collection), réduisant
  le besoin de smart-cast heuristique.
- **Value classes généralisées** (multi-champs, avec identité structurelle
  vérifiée), pour supprimer la distinction artificielle actuelle entre
  "value class à un seul champ" et "data class classique" qui alloue
  toujours sur le tas.
- **Réduction du boilerplate DSL** via une distinction syntaxique claire
  entre "lambda à récepteur simple" et "DSL scope", pour que les messages
  d'erreur du compilateur cessent d'exposer la mécanique interne des
  types de fonction étendus.

### 2. Optimisation du runtime

- **Ownership et emprunt vérifiés statiquement par défaut**, pas en
  option. Kotlin-X inverse la hiérarchie actuelle : le compilateur trace
  la durée de vie de chaque valeur à la compilation, et un GC
  concurrent, region-based, n'intervient que sur les sous-graphes
  d'objets où la vérification statique serait trop rigide (structures
  partagées entre agents, caches mutables long terme). Le ramasse-miettes
  devient l'exception gérée, pas le mécanisme par défaut qu'on débraye au
  cas par cas avec des drapeaux de compilation — ce qui met fin d'un coup
  au zoo des quatre collecteurs sélectionnables identifié en I.3/I.6.
- **Une seule représentation intermédiaire universelle**, pas un pivot
  bytecode JVM entouré de backends spécialisés. Le compilateur Kotlin-X
  cible directement, depuis cette IR unique : CPU (toutes architectures),
  GPU, accélérateurs NPU/TPU pour l'effet `infer`, et — pour les profils
  IoT les plus contraints — la synthèse directe de circuit (FPGA/ASIC),
  sans étape de repli vers un bytecode généraliste. Le bytecode JVM
  devient un backend de compatibilité parmi d'autres, jamais la
  représentation pivot.
- **Partage de code cross-binaire natif**, pour effacer la contrainte
  "monde fermé" de Kotlin/Native (I.3) : une ABI d'IR versionnée et
  stable permet le linkage incrémental et le chargement dynamique de
  modules compilés séparément — y compris à chaud, condition nécessaire
  au "liquid computing" du Pilier 3 de la Partie III.
- **Scheduler et allocateur auto-adaptatifs, pilotés par un modèle
  embarqué léger.** Plutôt que des heuristiques statiques figées à la
  compilation, le runtime Kotlin-X embarque un modèle de décision minimal
  (quelques kilooctets, lui-même un cas d'usage du Pilier 1) qui ajuste en
  continu la stratégie de collecte, la taille des régions et la priorité
  d'ordonnancement des agents en fonction du profil d'exécution réel —
  un runtime qui optimise l'exécution du programme comme un système
  d'IA optimiserait n'importe quelle autre charge de travail, plutôt
  qu'un ensemble de réglages manuels choisis une fois pour toutes par le
  développeur.

### 3. Outillage (toolchain)

- **Un seul moteur de résolution, une seule fois pour toutes.** Kotlin-X
  n'a jamais deux implémentations parallèles à synchroniser : le moteur
  FIR unique, paramétrable par granularité (batch/incrémental), sert à la
  fois le compilateur en ligne de commande et l'IDE — ce que la
  coexistence forcée K2/`low-level-api-fir` documentée en I.4 n'a jamais
  pu offrir, précisément parce qu'elle a dû composer avec un frontend
  hérité.
- **Compilateur, linter, formatter et assistant IA : un seul binaire.**
  Pas de ktlint, pas de detekt, pas de plugin tiers à faire converger —
  la vérité syntaxique, sémantique et stylistique vient d'une seule
  source. Le compilateur intègre nativement un assistant de complétion et
  de correction piloté par un modèle, entraîné sur le graphe IR réel du
  projet plutôt que sur des heuristiques textuelles externes au
  compilateur (LSP classique) : les suggestions "connaissent" les
  contrats, les effets et les budgets déclarés du code environnant.
- **Gestionnaire de paquets natif, content-addressed, sans Gradle.**
  Résolution de dépendances par hash de contenu (à la Nix/Cargo), pas de
  DSL Groovy/Kotlin à interpréter avant même de savoir quoi compiler.
  Gradle et le KGP actuel restent disponibles comme pont pour
  l'écosystème JVM existant, mais cessent d'être le chemin par défaut —
  un projet Kotlin-X natif ne dépend d'aucune JVM pour être construit.
- **Diagnostics et build reproductibles par défaut.** Chaque artefact
  Kotlin-X est reproductible bit à bit à partir de son graphe de
  dépendances content-addressed, condition nécessaire à la confiance dans
  un écosystème où du code peut migrer dynamiquement entre capteur, edge
  et cloud (Pilier 3, Partie III) : un nœud qui reçoit une continuation
  migrée doit pouvoir vérifier qu'elle correspond exactement au binaire
  attendu.

### 4. Stratégie de rupture maîtrisée : couper le cordon sans le désordre

Kotlin-X n'est pas une extension rétrocompatible de Kotlin : c'est une
rupture assumée avec le socle hérité de la JVM 2011, justifiée par
l'ampleur du changement de matériel et d'usage visé (accélérateurs IA,
essaims d'agents, flottes IoT) qu'aucune extension incrémentale de Kotlin
actuel ne peut satisfaire sans traîner indéfiniment le zoo de GC, les
platform types et la double vérité IDE/compilateur identifiés en I.6.
Le précédent à éviter n'est pas "changer trop vite", c'est l'inverse :
la transition Python 2 → 3 a fracturé son écosystème parce que la
coexistence *sans outillage de migration automatisé* a traîné près d'une
décennie, laissant chaque équipe réinventer sa propre voie de portage. La
leçon retenue ici n'est donc pas la prudence perpétuelle, c'est
l'outillage de la rupture :

1. **Un transpileur Kotlin → Kotlin-X assisté par le compilateur**, capable
   de réécrire automatiquement l'immense majorité du code existant (types
   nullables explicites, hiérarchies `sealed`, coroutines `suspend`
   standards) et de signaler avec précision les points qui exigent une
   décision humaine (usages de platform types ambigus, dépendance directe
   à un GC spécifique) — livré dès le premier jour, pas comme un projet
   séparé développé après coup.
2. **Une couche de pont JVM isolée et clairement temporaire**, permettant
   d'appeler du code Java/Kotlin existant depuis Kotlin-X sans réintroduire
   les platform types dans le cœur du langage — la dette d'interop reste
   circonscrite à cette couche, jamais diffusée dans le système de types
   natif.
3. **Un horizon de dépréciation court et public** (quelques versions
   majeures, pas "indéfiniment tant qu'un utilisateur en a besoin") : la
   rupture est annoncée, outillée, puis exécutée — pas repoussée
   indéfiniment au nom de la compatibilité.

Cette section n'est pas un détail de gestion de projet : elle est ce qui
distingue une rupture technologique assumée d'un simple abandon
désordonné des utilisateurs existants.

---

## Partie III — Kotlin-X : le nouveau paradigme

Le fil conducteur de Kotlin-X n'est pas d'empiler des bibliothèques IA,
agents et IoT par-dessus Kotlin existant — c'est de généraliser trois
mécanismes déjà réels dans ce compilateur : l'**IR** (représentation
intermédiaire unifiée, déjà partagée par tous les backends), la **state
machine des coroutines** (déjà un mécanisme de suspension/reprise
d'exécution), et le **système de contracts/effets** (déjà un canal pour
transmettre des invariants au frontend). Kotlin-X étend ces trois briques
jusqu'à leur limite logique, sans les brider par la rétrocompatibilité
actée en I.6/II.4.

Le matériel cible n'est plus, par défaut, "une JVM sur un serveur ou un
téléphone" : c'est un continuum hétérogène — cœurs CPU classiques,
accélérateurs NPU/TPU, microcontrôleurs à quelques dizaines de
kilooctets, et, à l'horizon de ce document, les premiers coprocesseurs
neuromorphiques et quantiques à sortir du laboratoire. Un langage conçu
pour ce continuum ne peut pas garder l'hypothèse implicite d'un unique
processeur généraliste synchrone qui a structuré la conception de Kotlin
classique ; Kotlin-X traite l'hétérogénéité matérielle comme une donnée
de premier ordre du système de types et du compilateur, pas comme une
préoccupation reportée à des bibliothèques d'accès bas niveau.

### Pilier 1 — Primitives IA au niveau du langage

**Le tenseur comme type de premier ordre, pas comme classe de
bibliothèque.** Kotlin-X introduit `Tensor<Shape, DType>` comme type
générique dépendant : `Shape` est un paramètre de type value-level vérifié
à la compilation (extension du système de types actuel avec des types
littéraux entiers, dans l'esprit des `const generics` de Rust), et `DType`
contraint les opérations arithmétiques disponibles. Une incompatibilité de
forme entre deux tenseurs devient une erreur de compilation, pas une
`RuntimeException` au milieu d'un entraînement.

```kotlin
// Vérification de forme statique : erreur de compilation si les
// dimensions ne s'accordent pas, pas d'exception au runtime.
fun attention(
    query: Tensor<Shape[B, T, D], Float32>,
    key:   Tensor<Shape[B, T, D], Float32>,
    value: Tensor<Shape[B, T, D], Float32>,
): Tensor<Shape[B, T, D], Float32> {
    val scores = query matmul key.transpose(-2, -1)  // Shape[B, T, T]
    val weights = scores.softmax(axis = -1)
    return weights matmul value                       // Shape[B, T, D]
}
```

**`infer` comme effet de langage, pas comme appel de bibliothèque.** Le
mot-clé `infer` marque un appel comme "délégable à un accélérateur
matériel", au même titre qu'`suspend` marque une fonction comme
"délégable au scheduler de coroutines". Le compilateur traite `infer` comme
une extension du système de contracts existant : la fonction annotée
s'engage sur une signature d'entrée/sortie tensorielle vérifiable, et le
backend choisit à la compilation (ou par profil au runtime, "PGA" —
*Profile-Guided Acceleration*, extension du profile-guided optimization
classique) la cible d'exécution : NPU, TPU, GPU, ou repli CPU vectorisé.

```kotlin
infer fun classify(image: Tensor<Shape[224, 224, 3], UInt8>): Label
    using model = "resnet50-quantized.ktm"

// Le compilateur émet, sans code manuel :
//  - la conversion mémoire zéro-copie vers le format attendu par le NPU
//    si le modèle cible et l'accélérateur détecté le permettent,
//  - un repli vectorisé CPU (SIMD via l'IR, déjà backend-agnostique)
//    si aucun accélérateur n'est présent à l'exécution,
//  - un cache de compilation du graphe de modèle par accélérateur
//    détecté, invalidé par hash du binaire modèle.
val label = classify(sensorFrame)
```

Ce mécanisme ne s'appuie pas sur `expect`/`actual` — abandonné en I.6 —
mais sur le système de capacités qui le remplace : une capacité
matérielle (`Accelerator<NPU>`, `Accelerator<Quantum>`) est un type
vérifiable, pas une correspondance nominale entre déclarations.

**Apprentissage continu comme primitive, pas comme pipeline MLOps
externe.** Un modèle déployé sur un agent ou un capteur devient
rapidement obsolète face à la dérive des données réelles. Plutôt que de
traiter le réentraînement comme un processus hors-langage (pipeline
MLOps séparé, redéploiement manuel), Kotlin-X expose le cycle
d'adaptation comme un effet du langage :

```kotlin
infer fun classify(image: Tensor<Shape[224, 224, 3], UInt8>): Label
    using model = "resnet50-quantized.ktm"
    adapt continuously from feedback: Flow<Correction>
        within budget { maxDrift = 2.percent; maxUpdateEnergy = 5.milliJoules }
```

Le compilateur émet la boucle de fine-tuning incrémental borné en
énergie/latence directement dans le binaire, avec les mêmes garanties de
budget statiquement vérifiées que le Pilier 3 applique au coût d'une
inférence — l'apprentissage continu cesse d'être une opération distincte
du programme pour devenir une propriété déclarée de la fonction elle-même.

**Préparation à l'hétérogénéité post-classique.** Sans prétendre que le
calcul neuromorphique ou quantique sera généralisé à l'horizon de ce
document, Kotlin-X réserve dès la conception un point d'extension du
système de capacités matérielles (`Accelerator<Neuromorphic>`,
`Accelerator<Quantum>`) pour que l'ajout d'un futur backend de ce type ne
requière pas de refonte du système de types — seulement une nouvelle
implémentation de la capacité, suivant exactement le mécanisme déjà
utilisé pour NPU/TPU.

### Pilier 2 — Programmation multi-agents native

Kotlin-X va au-delà du modèle acteur classique (boîte aux lettres +
traitement séquentiel de messages) en intégrant trois notions que le
modèle acteur laisse d'ordinaire à la charge du développeur : une mémoire
typée à deux horizons temporels, une politique de supervision déclarative,
et un canal de communication sémantique (pas seulement un tuyau de
messages opaques).

```kotlin
agent PricingAgent(initialCatalog: Catalog) {

    // Mémoire de travail : vit le temps d'une "conversation" logique,
    // gérée par le runtime comme un scope de coroutine structuré.
    memory short_term: ConversationContext = ConversationContext.empty()

    // Mémoire persistante : typée, versionnée, avec une politique de
    // rétention déclarée — le runtime gère la sérialisation et la
    // migration de schéma, pas le développeur.
    memory long_term: VectorStore<PriceHistory> by persistent(ttl = 90.days)

    // Politique de supervision déclarative : au-delà de "restart on
    // failure" du modèle acteur, contraintes de coût/latence explicites.
    supervision {
        onFailure(TimeoutException::class) { retry(times = 3, backoff = exponential) }
        onFailure(ModelUnavailable::class) { escalate(to = HumanReviewAgent) }
        budget { maxTokens = 50_000.perHour; maxLatency = 2.seconds }
    }

    // Canal de communication sémantique : le message porte une intention
    // typée, pas seulement une charge utile — le runtime route selon
    // capacité déclarée de l'agent destinataire, pas selon une adresse.
    on intent<RequestQuote> { request ->
        val context = short_term.recall(request.sessionId)
        val quote = infer { negotiate(request, context, long_term.similarTo(request)) }
        reply(quote)
    }
}

// Orchestration déclarative d'un système multi-agents : le runtime
// gère le déploiement, la découverte et le routage inter-agents.
agentscope RetailOps {
    spawn PricingAgent(catalog = mainCatalog)
    spawn InventoryAgent(warehouse = mainWarehouse)
    spawn HumanReviewAgent()

    // Politique de communication : les agents ne s'adressent jamais
    // directement, ils publient des intentions typées sur le scope.
    route<RequestQuote> to PricingAgent when { it.priority >= Priority.NORMAL }
}
```

Sur le plan de l'implémentation, `agent` se compile vers une extension de
la state machine de coroutine déjà existante (I.2) : chaque `on intent<T>`
devient un point de suspension potentiel, et `memory short_term` est géré
comme un `CoroutineContext` structuré mais *typé* et *introspectable* — ce
qui corrige justement la faiblesse documentée en I.2 (contexte implicite
non traçable statiquement). La mémoire long terme, elle, est un effet de
bord contrôlé : le compilateur exige une politique de rétention explicite
(`persistent(ttl = ...)`), rendant impossible d'oublier une politique de
purge — contrainte imposée au niveau du type, pas de la documentation.

**Gouvernance de la mémoire comme préoccupation de premier ordre.** Un
système multi-agents qui accumule de la mémoire long terme sans contrôle
devient, en production, un problème de conformité (RGPD, traçabilité des
décisions automatisées) avant d'être un problème de performance. Kotlin-X
traite cette contrainte comme un paramètre du type de la mémoire elle-même,
pas comme une politique externe documentée à part :

```kotlin
memory long_term: VectorStore<PriceHistory> by persistent(
    ttl = 90.days,
    retention = Retention.MinimizeNecessary,   // purge proactive, pas seulement TTL
    audit = AuditPolicy.LogEveryRecall,        // traçabilité exigée par le type
)
```

Le compilateur refuse de compiler un `recall()` sur une mémoire qui ne
déclare pas de politique d'audit si l'`agentscope` englobant est marqué
`@Regulated(gdpr = true)` — transformant une obligation de conformité,
aujourd'hui vérifiée a posteriori par des audits humains, en une erreur de
compilation.

**Communication inter-agents : consensus, pas seulement messages.** Le
routage par intention typée (`route<RequestQuote> to PricingAgent`)
suffit pour un pipeline simple, mais un système multi-agents réaliste doit
aussi gérer le désaccord entre agents (deux agents produisant des
diagnostics contradictoires sur la même donnée). Kotlin-X propose un bloc
`consensus` comme extension du modèle de canal, inspiré des protocoles de
vote distribué mais exprimé comme contrôle de flux natif :

```kotlin
val diagnosis = consensus(quorum = 2, of = 3) {
    vote { SensorAgent.assess(reading) }
    vote { HistoryAgent.assess(reading) }
    vote { ModelAgent.assess(reading) }
} resolveWith { votes -> votes.majorityOrEscalate(to = HumanReviewAgent) }
```

Ce mécanisme se compile vers un `select` structuré sur plusieurs coroutines
concurrentes (extension directe de `select { }`, déjà présent dans
l'API coroutines actuelle), avec une politique de résolution explicite
plutôt qu'une convention implicite laissée à la charge de chaque
application.

**Identité cryptographique native, pas un jeton applicatif ajouté après
coup.** Dans un système où des agents peuvent migrer (Pilier 3) et
négocier entre organisations différentes, l'identité d'un agent et
l'authenticité de ses messages ne peuvent pas rester une convention de
bibliothèque. Kotlin-X fait de la signature d'agent une propriété du
type : chaque `agent` est instancié avec une paire de clés générée par le
runtime, et chaque intention publiée sur un `agentscope` est signée
implicitement — un `agentscope` peut exiger `@Attested` pour rejeter à la
compilation toute route acceptant des agents non vérifiables.

**Essaims auto-organisés au-delà de l'orchestration centralisée.** Le
bloc `agentscope` de l'exemple précédent suppose un chef d'orchestre
explicite (`spawn`, `route`). Pour les charges de travail qui doivent
survivre à la perte d'un nœud central (essaim de drones, flotte de
capteurs autonomes), Kotlin-X propose un mode `swarm`, où la topologie de
routage n'est pas déclarée à l'avance mais négociée dynamiquement entre
agents pairs selon des capacités annoncées :

```kotlin
swarm FieldMonitoring {
    role sensorNode: SensingAgent
    role aggregator: AggregationAgent
        elect when { candidates -> candidates.maxByOrNull { it.batteryLevel } }

    // Pas de routage statique : chaque agent découvre ses pairs et
    // renégocie l'agrégateur si le nœud élu tombe sous un seuil
    // d'énergie — le runtime gère la réélection comme il gère
    // aujourd'hui la relance d'une coroutine annulée.
    resilience { reelectOn(condition = { aggregator.batteryLevel < 10.percent }) }
}
```

### Pilier 3 — IoT, edge computing et "liquid computing"

**Un sous-ensemble bare-metal du langage.** Kotlin-X définit un profil de
compilation restreint, "Kotlin/Tiny", ciblant les microcontrôleurs à
mémoire de quelques dizaines de kilooctets : pas de GC (allocation
statique/pool vérifiée à la compilation), pas de réflexion, ensemble
restreint de la stdlib avec des variantes `TinyML` des primitives
tensorielles du Pilier 1 (quantification `Int8`/`Int4` obligatoire,
tailles de tenseur bornées à la compilation).

```kotlin
@TinyTarget(arch = Cortex_M4, ramBudget = 64.KiB)
infer fun detectAnomaly(sample: Tensor<Shape[128], Int8>): Boolean
    using model = "anomaly-quantized.ktm"  // vérifié à la compilation :
                                            // taille du modèle ≤ budget flash déclaré
```

Le profil `Kotlin/Tiny` s'appuie directement sur la contrainte
d'allocation déjà nécessaire pour Kotlin/Native (I.3, allocateur
page-based) plutôt que de réinventer un modèle mémoire séparé : sur ce
profil, l'allocateur est configuré en mode `-Xgc=noop` avec une réserve
statique dimensionnée à la compilation (`ramBudget`), et toute allocation
qui dépasserait ce budget est une **erreur de compilation**, pas une
`OutOfMemoryError` observée en test d'intégration sur le matériel cible —
un déplacement de la vérification vers la gauche du cycle de
développement, cohérent avec le principe transversal déjà énoncé pour les
formes de tenseur et les budgets d'agent.

**Budget énergétique comme paramètre de premier ordre du type.** Sur un
capteur alimenté par batterie ou récupération d'énergie, le coût
énergétique d'une fonction `infer` n'est pas un détail d'implémentation
mais une contrainte de conception au même titre que sa forme d'entrée. Le
compilateur, disposant déjà du graphe IR et du modèle de coût utilisé pour
le "liquid computing" ci-dessous, peut estimer un ordre de grandeur
d'énergie par appel et le vérifier contre un budget déclaré :

```kotlin
@TinyTarget(arch = Cortex_M0, powerBudget = 12.microJoules.perInference)
infer fun detectAnomaly(sample: Tensor<Shape[128], Int8>): Boolean
    using model = "anomaly-quantized.ktm"
// Erreur de compilation si l'estimation statique du coût du modèle
// quantifié dépasse le budget déclaré pour cette architecture cible.
```

**"Liquid computing" : la migration transparente capteur → edge →
cloud.** L'idée centrale n'est pas un mécanisme de distribution ad hoc,
mais une extension directe du mécanisme de suspension/reprise déjà présent
dans les coroutines. Une fonction annotée `@migratable` est compilée avec
un point de sérialisation de continuation : son état d'exécution (variables
locales capturées, position dans la state machine) peut être sérialisé,
transporté, et repris sur un nœud d'exécution différent — exactement comme
une coroutine JVM sérialise son `label` et ses variables capturées entre
deux points de suspension, mais ici la "reprise" traverse une frontière
réseau et matérielle plutôt qu'un simple retour de call stack.

```kotlin
@migratable(preferred = [Sensor, Edge, Cloud])
suspend fun processReading(raw: SensorFrame): Diagnosis {
    val filtered = denoise(raw)                 // coût faible : reste sur Sensor
    checkpoint()                                  // point de migration explicite

    val features = infer { extractFeatures(filtered) }  // coût moyen : Edge si dispo
    checkpoint()

    return infer { fullDiagnosis(features) }     // coût élevé : Cloud si nécessaire
}
```

À chaque `checkpoint()`, le runtime évalue une politique déclarative
(latence disponible, budget énergétique restant du capteur, connectivité
réseau, coût de calcul de la suite de la fonction estimé par le
compilateur à partir du graphe IR) et décide de continuer l'exécution
localement ou de sérialiser la continuation vers le nœud suivant de la
liste `preferred`. La fonction reste écrite comme du code séquentiel
ordinaire ; la distribution physique est un effet géré par le runtime,
exactement comme la suspension d'une coroutine ordinaire est aujourd'hui
un effet géré par le scheduler sans que l'appelant n'ait à gérer de
callback explicite.

Ce mécanisme répond directement à une limite identifiée en I.3 : le modèle
de compilation "monde fermé" de Kotlin/Native, qui interdit aujourd'hui le
partage de code cross-binaire au-delà de l'étape de link. Le "liquid
computing" ne cherche pas à contourner cette contrainte au niveau binaire —
il déplace la granularité de migration au niveau de la *continuation
d'exécution sérialisée*, un problème déjà résolu structurellement par le
compilateur de coroutines, plutôt que d'exiger un nouveau format binaire
partagé entre capteur, edge et cloud.

**Synthèse silicium comme cible de compilation à part entière.** Pour les
capteurs les plus contraints — où même un microcontrôleur généraliste
est un luxe énergétique — Kotlin-X pousse la logique du profil
`Kotlin/Tiny` jusqu'à son terme : un sous-ensemble entièrement combinatoire
et sans état mutable non borné d'une fonction `@TinyTarget` peut être
compilé, non pas vers du code machine, mais directement vers une
description de circuit synthétisable (FPGA en développement, ASIC en
production de masse), en réutilisant le même graphe IR que les backends
CPU/GPU/NPU. Le développeur écrit une seule fois la logique de détection
d'anomalie ; le compilateur choisit, selon le profil de déploiement
déclaré, s'il émet un binaire pour microcontrôleur ou une description de
circuit pour un capteur sans processeur du tout.

**Autonomie de maillage : le cloud comme option, pas comme dépendance.**
Le "liquid computing" décrit plus haut suppose une liste `preferred` de
destinations, mais rien n'impose que `Cloud` en fasse partie. Un maillage
Kotlin-X (`swarm`, Pilier 2) peut fonctionner en autonomie complète,
capteur et edge se répartissant l'intégralité du calcul entre pairs
directement connectés — le cloud redevient ce qu'il devrait être pour un
système edge-first : un nœud parmi d'autres, disponible quand la
connectivité le permet, jamais un point de passage obligé pour qu'un
capteur produise un résultat exploitable.

Le budget mémoire déclaré (`ramBudget`) et le budget latence/coût déclaré
dans `supervision { budget { } }` (Pilier 2) partagent la même mécanique de
vérification statique que la forme d'un `Tensor` (Pilier 1) : ce sont tous
des invariants portés par le type ou par une annotation vérifiée au moment
de la compilation, pas des contrôles a posteriori à l'exécution. C'est ce
principe transversal — pousser la contrainte le plus tôt possible dans le
pipeline plutôt que de la reporter au runtime — qui distingue Kotlin-X
d'un simple assemblage de bibliothèques IA/agents/IoT.

### Un système d'effets unifié comme fondation commune aux trois piliers

`suspend`, `infer`, `agent` et `@migratable` ne sont pas quatre mécanismes
indépendants dans Kotlin-X : ce sont quatre déclinaisons d'un seul système
d'effets généralisé, où chaque mot-clé déclare *quelle ressource externe*
une fonction peut engager (scheduler de coroutine, accélérateur matériel,
mémoire d'agent, nœud d'exécution physique) et laisse le compilateur
composer ces effets plutôt que de les empiler comme des design patterns
manuels.

```kotlin
// La signature de type porte l'effet directement : le compilateur sait,
// avant toute exécution, que cette fonction peut suspendre (structured
// concurrency), déléguer à un accélérateur (infer) et migrer (liquid) —
// et peut donc rejeter à la compilation une composition invalide (par
// exemple, appeler cette fonction depuis un contexte @TinyTarget qui
// n'autorise pas l'effet Cloud du preferred list).
effect fun analyzeAndRoute(raw: SensorFrame): Diagnosis
    with suspend, infer, migratable(preferred = [Edge, Cloud])
```

Cette unification a un précédent réel dans ce compilateur : le système de
`contract { }` actuel sert déjà de canal générique pour transmettre des
invariants du corps d'une fonction vers le frontend (par ex.
`returns(true) implies (value != null)`). Généraliser ce canal en un
véritable système d'effets typés — plutôt que de multiplier les mots-clés
ad hoc — est la seule voie qui évite à Kotlin-X de retomber dans le travers
identifié en I.1 pour `Flow` : une prolifération d'API parallèles
(`StateFlow`/`SharedFlow`/`callbackFlow`) pour des variations d'un même
concept qui aurait dû rester unifié dès la conception.

### Scénario intégré : les trois piliers en un seul système

Pris séparément, chaque exemple précédent illustre un mécanisme isolé. Ce
qui justifie de les avoir conçus ensemble plutôt que comme trois
extensions indépendantes ne devient visible que sur un scénario complet.
Prenons "SentinelleAgricole" : un maillage de capteurs de stress hydrique
dans une parcelle agricole, sans connectivité cloud garantie, où chaque
capteur doit décider localement s'il faut déclencher l'irrigation ou
remonter un diagnostic plus coûteux.

```kotlin
// Pilier 3 : chaque capteur, un microcontrôleur à 64 KiB, exécute une
// première estimation bornée en énergie — silicium dédié si le capteur
// n'a même pas de microcontrôleur généraliste (I.3, synthèse FPGA/ASIC).
@TinyTarget(arch = Cortex_M0, ramBudget = 64.KiB, powerBudget = 12.microJoules.perInference)
infer fun localStressScore(reading: Tensor<Shape[16], Int8>): Tensor<Shape[1], Float32>
    using model = "stress-tinyquant.ktm"

// Pilier 2 : les capteurs d'une même parcelle forment un essaim sans
// chef fixe ; l'agrégateur est réélu si son niveau d'énergie chute —
// aucune dépendance à un orchestrateur central ou à une connexion cloud.
swarm ParcelMesh {
    role sensorNode: SensingAgent
    role aggregator: SoilHealthAgent
        elect when { candidates -> candidates.maxByOrNull { it.batteryLevel } }
    resilience { reelectOn(condition = { aggregator.batteryLevel < 10.percent }) }
}

// Pilier 2 (mémoire + consensus) : l'agrégateur élu confronte plusieurs
// sources avant de conclure — pas une lecture de capteur isolée.
agent SoilHealthAgent(zone: FieldZone) {
    memory short_term: SensorWindow = SensorWindow.lastHours(6)
    memory long_term: VectorStore<SeasonalPattern> by persistent(
        ttl = 365.days,
        retention = Retention.MinimizeNecessary,
    )

    on intent<StressAlert> { alert ->
        val diagnosis = consensus(quorum = 2, of = 3) {
            vote { localStressScore(alert.reading) }
            vote { infer { seasonalDrift(short_term, long_term.similarTo(alert)) } }
            vote { infer { peerCrossCheck(zone.neighbours) } }
        } resolveWith { votes -> votes.majorityOrEscalate(to = FarmerReviewAgent) }

        reply(diagnosis)
    }
}

// Système d'effets unifié : la fonction porte, dans sa signature, tous
// les effets qu'elle engage — suspension structurée, délégation
// matérielle, et migration vers l'edge ou le cloud si et seulement si
// la connectivité et le budget énergétique le permettent au moment du
// checkpoint (Pilier 3). Le cloud n'apparaît qu'en dernier choix.
effect fun irrigationDecision(alert: StressAlert): IrrigationPlan
    with suspend, infer, migratable(preferred = [Sensor, Edge, Cloud])
```

La lecture de ce scénario révèle ce que la Partie III ne pouvait pas
montrer pilier par pilier : le capteur qui déclenche l'alerte n'a besoin
ni d'un microcontrôleur puissant (Pilier 3, synthèse silicium), ni d'une
connexion cloud permanente (Pilier 3, autonomie de maillage), ni d'un
orchestrateur central (Pilier 2, `swarm`) pour produire une décision
fiable — le `consensus` (Pilier 2) compense l'incertitude d'une lecture
unique en confrontant plusieurs sources sans jamais quitter le maillage
local, et la fonction finale ne sollicite le cloud, via `checkpoint()`,
que si le diagnostic reste ambigu après consensus local. C'est cette
composition — pas chaque primitive isolément — qui constitue la
proposition de valeur de Kotlin-X : un système d'IA distribué, résilient
et sobre en énergie, écrit comme un seul programme séquentiel, sans
qu'aucune des trois couches (silicium, essaim, migration) n'ait dû être
assemblée à la main par le développeur.

### Risques et limites de la proposition

Une conception honnête doit énoncer ce qui rend Kotlin-X difficile, pas
seulement ce qui le rend souhaitable :

- **Vérification de forme de tenseur à la compilation** (Pilier 1) exige
  soit des types dépendants complets (coût d'implémentation comparable à
  Idris/Agda, très éloigné du budget d'ingénierie d'un compilateur
  industriel), soit un sous-ensemble restreint à l'arithmétique linéaire
  sur des dimensions connues statiquement — ce qui exclut par construction
  les formes dynamiques (séquences de longueur variable) sans mécanisme de
  repli vers une vérification au runtime, réintroduisant partiellement le
  problème que le mécanisme cherche à éliminer.
- **`agent` comme primitive de runtime** déplace vers le compilateur et le
  runtime des décisions aujourd'hui prises par des frameworks applicatifs
  (orchestration, retry, budget) — un choix qui accroît la surface de
  sécurité et de correction du runtime lui-même (un bug dans le scheduler
  d'agents devient un bug de langage, pas un bug de bibliothèque
  remplaçable) et qui doit être pesé contre la perte de flexibilité que
  cela impose aux architectures qui ne veulent pas de ce modèle par
  défaut.
- **Le "liquid computing"** suppose un contrat de sérialisation de
  continuation stable *à travers des architectures matérielles
  hétérogènes* (capteur 32 bits, edge x86/ARM, cloud), un problème plus
  proche de la portabilité d'un format IR de bytecode complet (à la
  wasm) que d'une simple sérialisation d'objet — et pose des questions de
  sécurité non résolues ici (un nœud edge compromis pourrait falsifier
  l'état d'une continuation reprise ailleurs), qui exigeraient un
  mécanisme d'attestation ou de signature de continuation non détaillé
  dans ce document.
- **Le coût de la nouveauté syntaxique** lui-même : chaque mot-clé ajouté
  (`agent`, `infer`, `effect`, `memory`, `agentscope`, `swarm`) est un coût
  d'apprentissage, que le transpileur automatisé de la Partie II.4 réduit
  pour le code existant mais ne peut pas éliminer pour les développeurs
  qui doivent apprendre à raisonner nativement dans ce nouveau paradigme.
- **La synthèse silicium et la promesse neuromorphique/quantique** (Pilier
  1 et Pilier 3) restent, honnêtement, la partie la plus spéculative de ce
  document : générer un circuit FPGA correct à partir d'un sous-ensemble
  de langage haut niveau est un problème de recherche actif (proche des
  HLS — *High-Level Synthesis* — existants, mais sans leur maturité
  éprouvée), et aucun coprocesseur quantique grand public n'existe
  aujourd'hui pour valider la capacité `Accelerator<Quantum>` proposée.
  Cette réserve du système de types ne coûte rien tant qu'elle reste
  inutilisée — mais rien ne garantit, à ce stade, qu'elle corresponde à
  la forme réelle qu'aura ce matériel quand il existera.

Ces limites ne sont pas des objections à la démarche, mais les conditions
que devrait remplir tout programme de recherche sérieux avant une
implémentation : chacune correspond à une question ouverte de théorie des
langages de programmation (vérification de formes dynamiques, sûreté d'un
scheduler d'agents typé, portabilité d'un format de continuation
cross-architecture) plutôt qu'à un simple détail d'ingénierie.

### Synthèse des primitives Kotlin-X

Le tableau suivant récapitule les nouvelles primitives proposées, le
mécanisme existant du compilateur qu'elles étendent, et la faiblesse de la
Partie I qu'elles adressent — pour vérifier, en un coup d'œil, qu'aucune
proposition de la Partie III ne reste sans justification tracée jusqu'au
diagnostic initial.

| Primitive Kotlin-X          | Étend                                       | Répond à                                    |
|-------------------------------|----------------------------------------------|------------------------------------------------|
| `Tensor<Shape, DType>`        | Génériques réifiés (`inline reified`)         | I.1 — absence de vérification de forme statique |
| `infer` / `using model`       | `contract { }`, `expect`/`actual`             | I.1 — érasure ; III — boilerplate d'accélération |
| `agent` / `on intent<T>`       | State machine de coroutine (I.2)              | I.2 — contexte implicite non typé               |
| `memory short_term`/`long_term`| `CoroutineContext` structuré                  | I.2 — traçabilité du contexte                   |
| `supervision { budget { } }`   | `CoroutineExceptionHandler`                    | I.2 — annulation coopérative non garantie       |
| `consensus { vote { } }`       | `select { }`                                   | Pilier 2 — désaccord inter-agents               |
| `@TinyTarget` / `ramBudget`    | Allocateur page-based Native (I.3)             | I.3 — GC/allocateur non stabilisé pour l'embarqué |
| `@TinyTarget` → synthèse FPGA/ASIC | IR unique multi-backend (II.2)             | I.6 — abandon du pivot bytecode JVM             |
| `swarm` / `elect` / `reelectOn`| `agentscope` centralisé                        | Pilier 2 — dépendance à un orchestrateur unique |
| `@Attested` (signature d'agent)| Système de capacités (remplace `expect`/`actual`) | I.6 — abandon d'`expect`/`actual`           |
| `@migratable` / `checkpoint()` | Sérialisation de continuation de coroutine     | I.3 — modèle de compilation "monde fermé"       |
| `effect fun ... with`          | `contract { }` généralisé                      | Cohérence transversale des quatre effets        |

---

## Conclusion

Kotlin-X n'est pas une évolution prudente de Kotlin : c'est une rupture
délibérée avec le socle hérité de la JVM 2011, dont le verdict
d'obsolescence est posé explicitement en I.6 — platform types, zoo de
GC, `expect`/`actual`, dépendance structurelle à Gradle, pivot bytecode
JVM — et dont le remplacement est conçu, dès le départ, pour le matériel
et les usages de la décennie à venir plutôt que pour la compatibilité
avec ceux de la décennie passée.

Cette rupture reste néanmoins de l'ingénierie, pas de la spéculation
gratuite : chaque primitive nouvelle généralise un mécanisme déjà réel et
éprouvé dans ce compilateur plutôt que d'inventer un mécanisme isolé.

- Les **tenseurs vérifiés statiquement**, l'effet `infer` et
  l'apprentissage continu embarqué étendent le système de contracts déjà
  présent, jusqu'à couvrir l'hétérogénéité matérielle (NPU/TPU, et à
  terme neuromorphique/quantique) comme une capacité de type vérifiable.
- Les mots-clés **`agent`** et **`swarm`** généralisent la state machine
  de coroutine déjà compilée par le backend, en la rendant introspectable,
  typée et capable de s'auto-organiser sans orchestrateur central — là où
  le `CoroutineContext` actuel reste un dynamic scoping opaque (I.2).
- Le **"liquid computing"**, la synthèse silicium et l'autonomie de
  maillage réutilisent le même mécanisme de sérialisation de continuation
  pour effacer, plutôt que contourner, la contrainte de modèle "monde
  fermé" documentée pour Kotlin/Native (I.3) — en déplaçant la frontière
  d'exécution du binaire figé vers la continuation migrable, et le cloud
  du statut de dépendance à celui d'option.

Un langage nativement pensé pour l'IA, les agents autonomes et l'IoT
omniprésent n'a pas besoin de porter indéfiniment les compromis d'un
écosystème JVM vieux de vingt-cinq ans. Kotlin-X fait le pari inverse :
garder ce que Kotlin a résolu mieux que quiconque — l'expressivité du
système de types, la discipline de la structured concurrency — et couper,
sans ambiguïté, tout le reste.
