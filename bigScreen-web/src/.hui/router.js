import { initRouter, initRouterLayout } from 'hui-core'
import TopologyPage from '@/views/TopologyPage.vue'
import InstancePage from '@/views/InstancePage.vue'

export default initRouter(
  [
    {
      path: '/',
      redirect: '/topology',
      component: initRouterLayout(layout => {
        return import('@/layouts/' + layout + '.vue')
      }),
      children: [
        {
          name: 'TopologyPage',
          path: 'topology',
          component: TopologyPage
        },
        {
          name: 'InstancePage',
          path: 'instances',
          component: InstancePage
        }
      ]
    }
  ],
  { base: '/', mode: 'hash' }
)
