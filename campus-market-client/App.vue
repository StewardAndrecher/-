<script>
	import { refreshNetworkStatus } from '@/utils/network-status.js'
	export default {
		onLaunch: function() {
			console.log('App Launch')
		},
		onShow: function() {
			refreshNetworkStatus()
			console.log('App Show')
		},
		onHide: function() {
			console.log('App Hide')
		}
	}
</script>

<style>
@import '@/styles/theme.css';
</style>
