import 'h_ui/dist/h_ui.min.css'
import hCore, { store } from 'hui-core';
import router from './router';
import '@/utils/import.js';

const app = hCore({
  extraModelOptions: {
    store
  }
});

app.router = router;
app.options.router = router;
if (!app.options.template) {
  app.options.template = '<router-view></router-view>';
}

app.start();
