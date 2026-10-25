# coding: utf-8
import importlib.util,json,sys,tempfile,unittest
from pathlib import Path
root=Path(__file__).resolve().parents[2]/'release/deployment'
def module(name):
    spec=importlib.util.spec_from_file_location(name,root/(name+'.py'));m=importlib.util.module_from_spec(spec);spec.loader.exec_module(m);return m
render=module('render_deployment');readiness=module('check_readiness')
class PublicDeploymentTests(unittest.TestCase):
    def test_real_origin_renders_private_upstream_and_overwritten_client_address(self):
        files=render.render('https://market.campus.edu')
        self.assertEqual({'nginx.site.conf','campus-market.service','runtime.env.example'},set(files))
        text=files['nginx.site.conf'];self.assertIn('proxy_pass http://127.0.0.1:8080;',text)
        self.assertIn('proxy_set_header X-Forwarded-For $remote_addr;',text);self.assertNotIn('$proxy_add_x_forwarded_for',text)
        self.assertIn('proxy_set_header Forwarded "";',text);self.assertIn('--spring.profiles.active=production',files['campus-market.service'])
        self.assertIn('DB_PASSWORD=\n',files['runtime.env.example'])
    def test_template_injection_placeholder_and_wrong_origins_rejected(self):
        for value in ['http://market.campus.edu','https://user:secret@market.campus.edu','https://market.campus.edu/a','https://market.invalid','https://localhost','https://127.0.0.1','https://market.campus.edu:8443','https://market.campus.edu;\nreturn 200;']:
            with self.subTest(value=value),self.assertRaises(ValueError):render.render(value)
    def test_backend_port_is_bounded(self):
        for value in [0,1023,65536,'8080',True]:
            with self.assertRaises(ValueError):render.render('https://market.campus.edu',value)
        self.assertIn('127.0.0.1:18080',render.render('https://market.campus.edu',18080)['nginx.site.conf'])
    def test_generated_locations_keep_security_headers_when_cache_headers_override_inheritance(self):
        text=render.render('https://market.campus.edu')['nginx.site.conf']
        self.assertEqual(5,text.count('add_header X-Content-Type-Options nosniff always;'))
        self.assertNotIn('SECURITY_HEADERS',text)
    def test_renderer_never_overwrites_existing_directory(self):
        with tempfile.TemporaryDirectory() as folder:
            output=Path(folder)/'existing';output.mkdir();sentinel=output/'keep';sentinel.write_text('untouched')
            args=['render_deployment.py','--origin','https://market.campus.edu','--output',str(output)]
            from unittest.mock import patch
            with patch.object(sys,'argv',args),self.assertRaises(FileExistsError):render.main()
            self.assertEqual('untouched',sentinel.read_text())
    def test_operational_check_rejects_http_except_explicit_loopback_preview(self):
        self.assertEqual('http://127.0.0.1:8080',readiness.base_origin('http://127.0.0.1:8080',True))
        for value in ['http://127.0.0.1:8080','http://market.campus.edu','https://user:secret@market.campus.edu','https://market.campus.edu/path']:
            with self.assertRaises(ValueError):readiness.base_origin(value)
    def test_simulation_cannot_pass_operational_payment_check(self):
        calls=[]
        def fetch(base,path):
            calls.append(path);return {'paymentMode':'SIMULATED','orderCreationEnabled':True,'simulatedPaymentEnabled':True} if path.endswith('trade-capabilities') else {'status':'UP'}
        result=readiness.check('https://market.campus.edu',fetch)
        self.assertFalse(result['technicalChecksPassed']);self.assertEqual(3,len(calls));self.assertEqual('SIMULATED',result['checks'][-1]['observedMode'])
    def test_unavailable_payment_is_reported_as_blocked(self):
        def fetch(base,path):return {'paymentMode':'UNAVAILABLE','orderCreationEnabled':False,'simulatedPaymentEnabled':False} if path.endswith('trade-capabilities') else {'status':'UP'}
        self.assertFalse(readiness.check('https://market.campus.edu',fetch)['technicalChecksPassed'])
    def test_network_failures_are_not_reported_as_payment_success_or_echoed_credentials(self):
        def fail(*args):raise ValueError('fixture-secret-must-not-appear')
        result=readiness.check('https://market.campus.edu',fail)
        self.assertFalse(result['technicalChecksPassed']);self.assertNotIn('fixture-secret',json.dumps(result))
if __name__=='__main__':unittest.main()
