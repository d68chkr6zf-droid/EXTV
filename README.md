# EXTV

Application Android TV de lecture IPTV avec le moteur vidéo **VLC (libVLC)** intégré.
Fonctions inspirées des lecteurs IPTV du marché, avec une interface et une marque propres.

> À utiliser uniquement avec des abonnements et contenus dont vous détenez les droits. L'application ne fournit aucun contenu.

## Fonctions

| Fonction | État |
|---|---|
| Connexion **Xtream Codes API** (serveur, identifiant, mot de passe) avec vérification du compte | ✅ |
| Connexion **liste M3U/M3U8** par URL, guide **XMLTV** (y compris `.gz` et `url-tvg`) | ✅ |
| **Multi-profils** (plusieurs comptes, favoris et reprises séparés) | ✅ |
| **TV en direct** : catégories, chaînes, logos, guide « maintenant / ensuite » avec barre de progression | ✅ |
| **Zapping** dans le lecteur (▲ ▼, CH+ / CH-) | ✅ |
| **Films** : catégories, affiches, fiche (résumé, genre, durée, casting), reprise de lecture | ✅ |
| **Séries** : saisons, épisodes, enchaînement automatique, reprise par épisode | ✅ |
| **Replay (catch-up)** : Xtream `timeshift` et M3U `catchup-source` / `shift` | ✅ |
| **Favoris** (chaînes, films, séries) | ✅ |
| **Recherche** globale (sans accents, insensible à la casse) | ✅ |
| **Contrôle parental** : code PIN, verrouillage par catégorie, verrouillage auto des catégories adultes | ✅ |
| **Lecteur VLC** : décodage matériel, mémoire tampon réglable, pistes audio, sous-titres, format d'image, User-Agent | ✅ |
| Navigation entière à la **télécommande** (D-pad) | ✅ |
| Interface **cinéma** (noir, velours rouge, or) adaptée **TV, tablette et smartphone** | ✅ |
| Accueil : tuiles TV en direct / Films / Séries avec date de mise à jour et bouton d'actualisation, expiration de l'abonnement | ✅ |
| TV en direct : catégories avec compteurs, Favoris, Historique, programme en cours, **aperçu vidéo intégré** | ✅ |
| Films / Séries : Récemment regardé, Récemment ajouté, notes sur les affiches, recherche dans les catégories | ✅ |
| **Guide TV** (programme en cours et suivant), **Replay** par catégorie, **Multi-écrans** (2 ou 4 chaînes) | ✅ |
| Appui long sur une chaîne / affiche : ajouter ou retirer des favoris | ✅ |
| Enregistrement, lecteur externe, mise à jour du guide en tâche de fond | À faire |

## Serveur par défaut
L'écran de connexion propose `http://718e.dataxtm.com` comme serveur Xtream (champ modifiable). Pour le changer, modifier `DEFAULT_SERVER` dans `app/build.gradle.kts`. Aucun identifiant n'est intégré dans l'application.

## Compiler

### Avec Android Studio (recommandé)
1. Ouvrir le dossier dans Android Studio (Ladybug ou plus récent).
2. Laisser Gradle synchroniser (JDK 17).
3. Choisir l'appareil « Android TV » (émulateur ou box sur le même réseau avec le débogage ADB activé) et lancer ▶.

### En ligne de commande
```bash
./gradlew assembleDebug
# APK : app/build/outputs/apk/debug/
adb connect <ip-de-la-box>
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

### Sans rien installer : GitHub Actions
Poussez le projet dans un dépôt GitHub : le workflow `.github/workflows/build.yml` compile l'APK à chaque push et le met à disposition dans l'onglet **Actions → Artifacts**.

## Télécommande dans le lecteur

| Touche | Action |
|---|---|
| OK | Afficher l'interface / pause |
| ◀ ▶ | Reculer / avancer de 10 s (30 s en restant appuyé) |
| ▲ ▼, CH+ / CH- | Chaîne précédente / suivante (live) |
| MENU ou INFO | Format d'image, pistes audio, sous-titres |
| Retour | Quitter le lecteur |

## Structure

```
app/src/main/java/com/extv/player/
├── App.kt                 Application, client HTTP, profil actif
├── MainActivity.kt        Point d'entrée Compose
├── data/
│   ├── Models.kt          Profils, chaînes, films, séries, guide, favoris
│   ├── IptvRepository.kt  Interface commune + recherche
│   ├── XtreamRepository.kt API player_api.php (catalogues, EPG, timeshift)
│   ├── M3uRepository.kt   Liste M3U + guide XMLTV + catch-up
│   ├── M3uParser.kt       Lecteurs M3U et XMLTV
│   └── LocalStore.kt      Profils, favoris, reprises, PIN, réglages
├── player/
│   ├── PlayQueue.kt       File de lecture (chaînes / épisodes)
│   └── PlayerActivity.kt  Lecteur libVLC + interface et menu d'options
└── ui/                    Écrans Compose (accueil, live, films, séries, favoris, recherche, paramètres)
```

## Notes techniques
- `libvlc-all` ajoute environ 30 à 40 Mo par architecture ; le build génère un seul APK (`EXTV.apk`) compatible avec tous les appareils.
- Le code PIN par défaut est **0000** (modifiable dans Paramètres).
- Les mots de passe sont stockés dans les SharedPreferences de l'application ; passer à `EncryptedSharedPreferences` avant une diffusion publique.
- Licence : libVLC est sous LGPL 2.1+. Une application qui l'embarque doit mentionner cette licence et permettre le remplacement de la bibliothèque (ce qui est le cas avec la dépendance Maven dynamique).
