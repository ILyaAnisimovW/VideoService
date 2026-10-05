#!/usr/bin/env python3
"""Validate design contracts/examples/links. Does not test backend behavior."""
from pathlib import Path
import json
import re
import sys
import yaml
from jsonschema import Draft202012Validator, FormatChecker
from openapi_spec_validator import validate

ROOT = Path(__file__).resolve().parents[1]
api = yaml.safe_load((ROOT / 'docs/api/openapi.yaml').read_text(encoding='utf-8'))
validate(api)
counts = {'operations': 0, 'examples': 0, 'links': 0, 'event_examples': 0}
ids = set()

def check_refs(node, root):
    if isinstance(node, dict):
        if '$ref' in node:
            target = node['$ref']
            if not target.startswith('#/'):
                raise AssertionError(f'Unexpected external reference: {target}')
            cursor = root
            for part in target[2:].split('/'):
                cursor = cursor[part.replace('~1', '/').replace('~0', '~')]
        for value in node.values():
            check_refs(value, root)
    elif isinstance(node, list):
        for value in node:
            check_refs(value, root)

check_refs(api, api)

def validate_example(schema, instance):
    local = {**schema, 'components': api['components']}
    Draft202012Validator(local, format_checker=FormatChecker()).validate(instance)
    counts['examples'] += 1

for schema in api['components']['schemas'].values():
    if 'example' in schema:
        validate_example(schema, schema['example'])

for path, item in api['paths'].items():
    for method, op in item.items():
        if method not in {'get','post','put','patch','delete','head','options','trace'}:
            continue
        counts['operations'] += 1
        assert op['operationId'] not in ids, f"Duplicate operationId: {op['operationId']}"
        ids.add(op['operationId'])
        assert any(status.startswith('2') for status in op['responses'])
        params=[]
        for param in item.get('parameters', []) + op.get('parameters', []):
            if '$ref' in param:
                param = api['components']['parameters'][param['$ref'].split('/')[-1]]
            params.append(param)
        assert {p['name'] for p in params if p['in']=='path'} == set(re.findall(r'\{([^}]+)\}', path)), path
        for param in params:
            if 'example' in param:
                validate_example(param['schema'],param['example'])
        containers = [op.get('requestBody', {})] + list(op['responses'].values())
        for container in containers:
            if '$ref' in container:
                container=api['components']['responses'][container['$ref'].split('/')[-1]]
            for media in container.get('content', {}).values():
                if 'example' in media:
                    validate_example(media['schema'],media['example'])

es = json.loads((ROOT/'docs/api/events.schema.json').read_text(encoding='utf-8'))
Draft202012Validator.check_schema(es)
ev=Draft202012Validator(es,format_checker=FormatChecker())
asyncapi=yaml.safe_load((ROOT/'docs/api/asyncapi.yaml').read_text(encoding='utf-8'))
check_refs(asyncapi,asyncapi)
for name, sch in es['$defs'].items():
    for example in sch.get('examples', []):
        ev.validate(example)
        counts['event_examples'] += 1
    payload=asyncapi['components']['messages'][name]['payload']
    assert payload == sch, f'AsyncAPI/JSON Schema mismatch: {name}'

# Heading anchors are checked for Markdown; external URLs are deliberately not fetched.
def anchors(text):
    out=set()
    for heading in re.findall(r'^#{1,6}\s+(.+)$',text,re.M):
        slug = re.sub(r'[^\w\s-]', '', heading.lower()).replace(' ', '-')
        out.add(slug)
    return out
for md in ROOT.rglob('*.md'):
    text=md.read_text(encoding='utf-8')
    for href in re.findall(r'\[[^\]]*\]\(([^)]+)\)',text):
        if re.match(r'^[a-zA-Z][a-zA-Z0-9+.-]*:',href):
            continue
        filepart, _, anchor=href.partition('#')
        target=(md.parent/filepart).resolve() if filepart else md
        assert target.is_file(), f'Broken link {md.relative_to(ROOT)} -> {href}'
        if anchor and target.suffix=='.md':
            assert anchor in anchors(target.read_text(encoding='utf-8')), f'Broken anchor {href}'
        counts['links']+=1

print(json.dumps({'status':'passed',**counts},ensure_ascii=False))
