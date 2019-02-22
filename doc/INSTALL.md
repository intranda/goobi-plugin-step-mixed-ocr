# Installation

Für die Installation muss eine Tabelle in der Goobi-Datenbank angelegt werden:

```sql
CREATE TABLE IF NOT EXISTS `ocrjobs` 
	( `ocrjob_id` int(11) NOT NULL AUTO_INCREMENT, 
	`step_id` int(11) NOT NULL, 
	`fracture_done` tinyint(1) DEFAULT false, 
	`antiqua_done` tinyint(1) DEFAULT false, 
	PRIMARY KEY (`ocrjob_id`) ) DEFAULT CHARSET=utf8mb4;
```

Die jar-Datei muss in den `goobi/plugins/step` Ordner gelegt werden. 