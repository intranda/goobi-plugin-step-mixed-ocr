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

Außerdem sollte noch die Goobi REST config unter `/opt/digiverso/goobi/config/goobi_rest.xml` angepasst werden, damit der TaskManager die REST endpoints dieses Plugins erreichen kann:

```xml
<endpoint path="/plugins/ocr.*">
    <method name="post">
        <allow netmask="127.0.0.0/8" token="Xasheax7ai"/>
    </method>
</endpoint>
```