import sys
from googletrans import Translator

text = sys.argv[1]
lang = sys.argv[2]
translator = Translator()
print(translator.translate(text, dest=lang).text)
