from pathlib import Path
import re
xml = Path(''wasfrontscenario/wasfront_config.xml'').read_text(encoding='utf-8')
cleaned = re.sub(r'(?is)<\s*module\s+name\s*=\s*"counts"\s*>.*?<\s*/\s*module\s*>', '', xml)
cleaned = cleaned.replace('module name="controller"', 'module name="controler"')
cleaned = re.sub(r'(?is)<\s*param\s+name\s*=\s*"enable"\s+value\s*=\s*"(true|false)"\s*/?\s*>', '', cleaned)
Path(''cleaned_preview.xml'').write_text(cleaned, encoding=''utf-8'')
