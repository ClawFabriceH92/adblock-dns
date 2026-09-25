# Règles R8 de l'application. Room, WorkManager, DataStore et Compose fournissent leurs
# propres règles (consumer rules) ; le module :core n'utilise ni réflexion ni sérialisation.

# Conserver les numéros de ligne dans les traces d'erreur (journal logcat lisible).
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
