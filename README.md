# Epub Merger

An Android app for combining the best parts of several .epub copies of the *same*
book into one clean file. Built with Kotlin + Jetpack Compose.

## The problem it solves

You have, say, three copies of *Congo*: one with a great cover but no chapter
breaks, one with clean formatting but no cover, and one with good chapter
structure but ugly CSS. Epub Merger lets you import all three, compare them
side by side, and pick which one supplies:

- the **cover image**
- the **title/author metadata**
- the **stylesheet / formatting**
- the **chapter structure and body text** (the "base" book)

If (and only if) all the imported books happen to have the exact same number
of chapters, you also get a per-chapter override list, so you can swap in an
individual chapter's text from a different source book.

It then assembles a brand new, valid, spec-compliant `.epub` (OPF package
document, NCX + EPUB3 nav, re-linked stylesheet, re-numbered chapter files)
and lets you save it anywhere via Android's normal "Save As" file picker.

## What it does NOT do (yet)

Real cross-edition text splicing — e.g. taking the *chapter break positions*
from Book A and applying them to the *running text* of Book B when the two
have genuinely different pagination/scans — needs real text alignment (think
diff/fuzzy-matching algorithms). That's a real feature but a much bigger one,
and isn't in this v1. What v1 handles well is the much more common situation:
"this file's actual content and structure is good, I just want a different
cover/metadata/stylesheet on it," plus whole-chapter substitution when chapter
counts line up.

## Project layout

```
app/src/main/java/com/epubmerger/app/
  model/EpubModels.kt      – data classes (EpubBook, Chapter, MergeSelection)
  epub/EpubParser.kt       – reads a .epub zip into an EpubBook
  epub/EpubBuilder.kt      – writes a new .epub from a MergeSelection
  ui/AppViewModel.kt       – app state (loaded books, selections, build status)
  ui/ImportScreen.kt       – pick epub files
  ui/CompareScreen.kt      – side-by-side comparison cards
  ui/MergeScreen.kt        – pick sources per component + export
  MainActivity.kt          – Compose navigation host
```

No third-party EPUB library is used — the parser/builder are hand-rolled on
top of `java.util.zip`, Android's built-in `XmlPullParser`, and
[Jsoup](https://jsoup.org/) (for reading/cleaning chapter HTML). This keeps
the app small and keeps full control over exactly what gets written into the
merged file.

## Building

### Via GitHub Actions (recommended, no local setup needed)

Push this repo to GitHub. `.github/workflows/build.yml` will automatically
build a debug APK and attach it as a workflow artifact — check the "Actions"
tab of the repo after pushing, click the latest run, and download
`epub-merger-debug-apk` from the "Artifacts" section at the bottom of the run
page. Unzip it to get `app-debug.apk`, then install it on an Android device
(you'll need to allow "install from unknown sources" the first time).

You can also trigger a build manually from the Actions tab
(`workflow_dispatch`) without pushing new commits.

### Locally

Open the project folder in Android Studio (Koala or newer) and hit Run, or
from the command line with Android Studio's SDK installed:

```
./gradlew assembleDebug
```

(Note: this repo does not ship a `gradlew` wrapper jar binary — Android
Studio will generate one for you on first open, or add
`gradle wrapper --gradle-version 8.7` yourself if you want a wrapper.)

## Signing / Play Store

The CI workflow only produces a **debug** build (fine for sideloading on your
own device). If you want a signed release build, that requires a keystore —
happy to add that step once you have one, but it needs secrets you wouldn't
want committed to the repo.
