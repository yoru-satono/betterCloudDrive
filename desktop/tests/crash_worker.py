"""Disposable real Engine subprocess. Test credentials enter only via stdin."""
import json
import os
from pathlib import Path
import sys

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from engine import Engine
from network import DesktopError


def reply(value):
    print(json.dumps(value), flush=True)


def main():
    config = json.loads(sys.stdin.readline())
    engine = Engine(config['root'], config['base'])
    try:
        engine.authenticate(config.pop('token'), engine.api_base)
        reply({'pid': os.getpid(), 'ready': True})
        for line in sys.stdin:
            data = json.loads(line)
            try:
                operation = data['op']
                if operation == 'queue':
                    result = engine.queue()
                elif operation == 'settings':
                    result = engine.save_settings(data['settings'])
                elif operation == 'upload':
                    result = engine.upload_files(data['paths'], {'apiBaseUrl': engine.api_base}, folder=data.get('folder', False))
                elif operation == 'download':
                    result = engine.download(Path(data['target']), {'apiBaseUrl': engine.api_base}, tree=data['tree'])
                elif operation == 'resume':
                    result = engine.resume(data['id'])
                elif operation == 'close':
                    reply({'closed': True})
                    break
                else:
                    raise DesktopError('UNKNOWN_WORKER_COMMAND')
                reply({'result': result})
            except Exception as error:
                reply({'error': str(error) if isinstance(error, DesktopError) else 'WORKER_COMMAND_FAILED'})
    finally:
        engine.close()


if __name__ == '__main__':
    main()
