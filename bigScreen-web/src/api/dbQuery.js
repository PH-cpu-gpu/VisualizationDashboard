import { rpc } from '@/utils/request'

export const dbQueryApi = {
  getTopologyData() {
    return rpc.call('/api/monitor/topology/data')
  },

  getNodeStatus(nodeId) {
    return rpc.call('/api/monitor/topology/nodeStatus', { nodeId })
  },

  getLinkMetrics(linkId) {
    return rpc.call('/api/monitor/topology/linkMetrics', { linkId })
  },

  getNodeMetrics(nodeId) {
    return rpc.call('/api/monitor/topology/nodeMetrics', { nodeId })
  },

  batchGetNodeStatus(nodeIds) {
    return rpc.call('/api/monitor/topology/batchNodeStatus', { nodeIds })
  }
}

export default dbQueryApi
